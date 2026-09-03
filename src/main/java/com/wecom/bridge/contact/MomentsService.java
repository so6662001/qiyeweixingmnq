package com.wecom.bridge.contact;

import com.fasterxml.jackson.databind.JsonNode;
import com.wecom.bridge.config.BridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户朋友圈：创建发表任务、查询发表记录、汇总点赞与评论。
 *
 * <p>官方模型是「企业创建任务 → 成员在企微客户端确认后发表」，接口本身不会直接发出去。</p>
 */
@Service
public class MomentsService {

    private static final Logger log = LoggerFactory.getLogger(MomentsService.class);

    /** get_moment_list 单次最大 20 条。 */
    private static final int LIST_LIMIT = 20;
    private static final int MAX_PAGES = 20;

    /** 官方限制：起止时间间隔不能超过 30 天。 */
    private static final int MAX_RANGE_DAYS = 30;

    private final BridgeProperties properties;
    private final WecomContactClient client;

    public MomentsService(BridgeProperties properties, WecomContactClient client) {
        this.properties = properties;
        this.client = client;
    }

    public boolean configured() {
        return client.configured();
    }

    /**
     * 创建朋友圈发表任务。成员需在企微客户端确认后才会真正发表。
     */
    public MomentModels.MomentTaskCreated createTask(MomentModels.MomentDraft draft) {
        client.requireConfigured();
        if (draft == null || !draft.hasContent()) {
            throw new ContactApiException("朋友圈内容为空：至少要有文本、图片或链接之一");
        }
        if (draft.imageMediaIds() != null && draft.imageMediaIds().size() > 9) {
            throw new ContactApiException("朋友圈图片最多 9 张");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (draft.text() != null && !draft.text().isBlank()) {
            body.put("text", Map.of("content", draft.text()));
        }

        List<Map<String, Object>> attachments = new ArrayList<>();
        if (draft.imageMediaIds() != null) {
            draft.imageMediaIds().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(id -> attachments.add(Map.of("msgtype", "image", "image", Map.of("media_id", id))));
        }
        if (draft.linkUrl() != null && !draft.linkUrl().isBlank()) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("title", draft.linkTitle() == null ? "" : draft.linkTitle());
            link.put("url", draft.linkUrl());
            if (draft.linkMediaId() != null && !draft.linkMediaId().isBlank()) {
                link.put("media_id", draft.linkMediaId());
            }
            attachments.add(Map.of("msgtype", "link", "link", link));
        }
        if (!attachments.isEmpty()) {
            body.put("attachments", attachments);
        }

        Map<String, Object> visibleRange = new LinkedHashMap<>();
        Map<String, Object> senderList = new LinkedHashMap<>();
        if (draft.senderUserIds() != null && !draft.senderUserIds().isEmpty()) {
            senderList.put("user_list", draft.senderUserIds());
        }
        if (draft.senderDepartmentIds() != null && !draft.senderDepartmentIds().isEmpty()) {
            senderList.put("department_list", draft.senderDepartmentIds());
        }
        if (!senderList.isEmpty()) {
            visibleRange.put("sender_list", senderList);
        }
        if (draft.customerTagIds() != null && !draft.customerTagIds().isEmpty()) {
            visibleRange.put("external_contact_list", Map.of("tag_list", draft.customerTagIds()));
        }
        if (!visibleRange.isEmpty()) {
            body.put("visible_range", visibleRange);
        }

        if (properties.isDryRun()) {
            log.info("[dry-run] 跳过创建朋友圈任务，内容长度 {}，图片 {} 张",
                    draft.text() == null ? 0 : draft.text().length(),
                    draft.imageMediaIds() == null ? 0 : draft.imageMediaIds().size());
            return new MomentModels.MomentTaskCreated("dry-run-job", true);
        }

        JsonNode response = client.post("/cgi-bin/externalcontact/add_moment_task", body);
        String jobId = response.path("jobid").asText("");
        if (jobId.isBlank()) {
            throw new ContactApiException("创建朋友圈任务未返回 jobid");
        }
        return new MomentModels.MomentTaskCreated(jobId, false);
    }

    /**
     * 查询任务创建结果。创建是异步的，需要轮询到 status=3 才能拿到 moment_id。
     */
    public MomentModels.MomentTaskStatus taskStatus(String jobId) {
        client.requireConfigured();
        if (jobId == null || jobId.isBlank()) {
            throw new ContactApiException("jobid 不能为空");
        }
        JsonNode response = client.get("/cgi-bin/externalcontact/get_moment_task_result", Map.of("jobid", jobId));

        int status = response.path("status").asInt(0);
        JsonNode result = response.path("result");
        List<String> invalid = new ArrayList<>();
        JsonNode invalidSenders = result.path("invalid_sender_list").path("user_list");
        if (invalidSenders.isArray()) {
            invalidSenders.forEach(node -> invalid.add(node.asText("")));
        }
        return new MomentModels.MomentTaskStatus(
                status, statusText(status), result.path("moment_id").asText(""), List.copyOf(invalid));
    }

    private static String statusText(int status) {
        return switch (status) {
            case 1 -> "开始创建";
            case 2 -> "创建中";
            case 3 -> "创建完成";
            default -> "未知状态 " + status;
        };
    }

    /**
     * 获取一段时间内的发表记录。
     *
     * @param filterType 0 企业发表 / 1 个人发表 / 2 全部
     */
    public List<MomentModels.MomentSummary> listMoments(long startTime, long endTime, String creator, int filterType) {
        client.requireConfigured();
        long maxRange = Duration.ofDays(MAX_RANGE_DAYS).toSeconds();
        if (endTime <= startTime) {
            throw new ContactApiException("结束时间必须晚于开始时间");
        }
        if (endTime - startTime > maxRange) {
            throw new ContactApiException("查询区间不能超过 " + MAX_RANGE_DAYS + " 天（官方限制）");
        }

        List<MomentModels.MomentSummary> moments = new ArrayList<>();
        String cursor = "";
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("start_time", startTime);
            body.put("end_time", endTime);
            body.put("filter_type", filterType);
            body.put("limit", LIST_LIMIT);
            if (creator != null && !creator.isBlank()) {
                body.put("creator", creator);
            }
            if (!cursor.isBlank()) {
                body.put("cursor", cursor);
            }

            JsonNode response = client.post("/cgi-bin/externalcontact/get_moment_list", body);
            JsonNode list = response.path("moment_list");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    moments.add(toSummary(item));
                }
            }
            cursor = response.path("next_cursor").asText("");
            if (cursor.isBlank()) {
                break;
            }
        }
        return moments;
    }

    private MomentModels.MomentSummary toSummary(JsonNode item) {
        JsonNode images = item.path("image");
        return new MomentModels.MomentSummary(
                item.path("moment_id").asText(""),
                item.path("creator").asText(""),
                item.path("create_time").asLong(0L),
                item.path("create_type").asInt(0),
                item.path("visible_type").asInt(0),
                item.path("text").path("content").asText(""),
                images.isArray() ? images.size() : 0,
                item.path("link").path("title").asText("")
        );
    }

    /**
     * 汇总一条朋友圈的互动数据。
     *
     * <p>官方的互动数据接口是按「发表成员」维度查的，所以要先取到已发表成员列表，
     * 再逐个累加点赞与评论。</p>
     */
    public MomentModels.MomentStats collectStats(String momentId, String creatorFallback) {
        client.requireConfigured();
        if (momentId == null || momentId.isBlank()) {
            throw new ContactApiException("moment_id 不能为空");
        }

        Set<String> senders = publishedSenders(momentId);
        if (senders.isEmpty() && creatorFallback != null && !creatorFallback.isBlank()) {
            // 个人创建的朋友圈没有任务执行列表，创建人本身就是发表成员
            senders = new LinkedHashSet<>(List.of(creatorFallback));
        }

        int likeCount = 0;
        int commentCount = 0;
        int customerLikes = 0;
        int customerComments = 0;
        List<MomentModels.MomentComment> comments = new ArrayList<>();

        for (String sender : senders) {
            JsonNode response;
            try {
                response = client.post("/cgi-bin/externalcontact/get_moment_comments",
                        Map.of("moment_id", momentId, "userid", sender));
            } catch (ContactApiException e) {
                log.info("获取朋友圈互动数据失败 moment={} sender={}: {}", momentId, sender, e.getMessage());
                continue;
            }

            JsonNode likeList = response.path("like_list");
            if (likeList.isArray()) {
                for (JsonNode like : likeList) {
                    likeCount++;
                    if (!like.path("external_userid").asText("").isBlank()) {
                        customerLikes++;
                    }
                }
            }

            JsonNode commentList = response.path("comment_list");
            if (commentList.isArray()) {
                for (JsonNode comment : commentList) {
                    commentCount++;
                    String externalUserId = comment.path("external_userid").asText("");
                    if (!externalUserId.isBlank()) {
                        customerComments++;
                    }
                    comments.add(new MomentModels.MomentComment(
                            sender,
                            externalUserId,
                            comment.path("userid").asText(""),
                            comment.path("content").asText(""),
                            comment.path("create_time").asLong(0L)));
                }
            }
        }

        comments.sort((a, b) -> Long.compare(b.createTime(), a.createTime()));
        return new MomentModels.MomentStats(momentId, creatorFallback, 0L, "", senders.size(),
                likeCount, commentCount, customerLikes, customerComments,
                List.copyOf(comments), System.currentTimeMillis(), null);
    }

    /**
     * 取一条朋友圈已发表的成员列表。
     */
    private Set<String> publishedSenders(String momentId) {
        Set<String> senders = new LinkedHashSet<>();
        String cursor = "";
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("moment_id", momentId);
            body.put("limit", 1000);
            if (!cursor.isBlank()) {
                body.put("cursor", cursor);
            }

            JsonNode response;
            try {
                response = client.post("/cgi-bin/externalcontact/get_moment_task", body);
            } catch (ContactApiException e) {
                // 个人发表的朋友圈没有任务，取不到属正常
                log.debug("获取朋友圈成员执行情况失败 moment={}: {}", momentId, e.getMessage());
                break;
            }

            JsonNode taskList = response.path("task_list");
            if (taskList.isArray()) {
                for (JsonNode task : taskList) {
                    // publish_status：0 未发表，1 已发表
                    if (task.path("publish_status").asInt(0) == 1) {
                        String userId = task.path("userid").asText("");
                        if (!userId.isBlank()) {
                            senders.add(userId);
                        }
                    }
                }
            }
            cursor = response.path("next_cursor").asText("");
            if (cursor.isBlank()) {
                break;
            }
        }
        return senders;
    }
}

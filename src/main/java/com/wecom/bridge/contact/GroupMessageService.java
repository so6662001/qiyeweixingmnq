package com.wecom.bridge.contact;

import com.fasterxml.jackson.databind.JsonNode;
import com.wecom.bridge.config.BridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户群群发图文消息。
 *
 * <p>官方模型：接口只是**创建群发任务**，群主（成员）需在企微客户端确认后才真正发到群里。
 * 官方还有频控（同一客户群每月可接收条数有限），这里把失败原因原样带回页面。</p>
 */
@Service
public class GroupMessageService {

    private static final Logger log = LoggerFactory.getLogger(GroupMessageService.class);

    private static final int MAX_ATTACHMENTS = 9;
    private static final int MAX_PAGES = 20;

    private final BridgeProperties properties;
    private final WecomContactClient client;

    public GroupMessageService(BridgeProperties properties, WecomContactClient client) {
        this.properties = properties;
        this.client = client;
    }

    public boolean configured() {
        return client.configured();
    }

    /**
     * 客户群列表。带上名称便于在页面上挑选。
     *
     * @param ownerUserIds 群主过滤，可空
     */
    public List<GroupChat> listGroupChats(List<String> ownerUserIds, int limit) {
        client.requireConfigured();
        List<GroupChat> chats = new ArrayList<>();
        String cursor = "";

        for (int page = 0; page < MAX_PAGES && chats.size() < limit; page++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status_filter", 0);
            body.put("limit", Math.min(Math.max(limit, 1), 1000));
            if (ownerUserIds != null && !ownerUserIds.isEmpty()) {
                body.put("owner_filter", Map.of("userid_list", ownerUserIds));
            }
            if (!cursor.isBlank()) {
                body.put("cursor", cursor);
            }

            JsonNode response = client.post("/cgi-bin/externalcontact/groupchat/list", body);
            JsonNode list = response.path("group_chat_list");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    String chatId = item.path("chat_id").asText("");
                    if (!chatId.isBlank()) {
                        chats.add(new GroupChat(chatId, "", item.path("status").asInt(0)));
                    }
                }
            }
            cursor = response.path("next_cursor").asText("");
            if (cursor.isBlank()) {
                break;
            }
        }

        // 名称需要单独查详情，条数多时只补前若干条，避免打爆接口频率
        int detailBudget = Math.min(chats.size(), 50);
        for (int i = 0; i < detailBudget; i++) {
            GroupChat chat = chats.get(i);
            try {
                JsonNode detail = client.post("/cgi-bin/externalcontact/groupchat/get",
                        Map.of("chat_id", chat.chatId(), "need_name", 1));
                String name = detail.path("group_chat").path("name").asText("");
                int memberCount = detail.path("group_chat").path("member_list").size();
                chats.set(i, new GroupChat(chat.chatId(),
                        name.isBlank() ? "（未命名群聊）" : name, memberCount));
            } catch (ContactApiException e) {
                log.debug("读取客户群详情失败 chat={}: {}", chat.chatId(), e.getMessage());
            }
        }
        return chats;
    }

    /**
     * 向客户群创建群发任务。
     *
     * @param chatIds  目标客户群
     * @param text     文本内容，可为空但不能与附件同时为空
     * @param images   图片附件（media_id 或 pic_url 二选一）
     * @param link     图文链接附件，可为空
     * @param sender   指定发送成员 userid，可为空
     */
    public GroupSendResult send(List<String> chatIds, String text, List<ImageAttachment> images,
                                LinkAttachment link, String sender) {
        client.requireConfigured();
        if (chatIds == null || chatIds.isEmpty()) {
            throw new ContactApiException("请至少选择一个客户群");
        }
        boolean hasText = text != null && !text.isBlank();
        boolean hasImages = images != null && !images.isEmpty();
        boolean hasLink = link != null && link.url() != null && !link.url().isBlank();
        if (!hasText && !hasImages && !hasLink) {
            throw new ContactApiException("文本与附件不能同时为空");
        }

        List<Map<String, Object>> attachments = new ArrayList<>();
        if (hasImages) {
            for (ImageAttachment image : images) {
                Map<String, Object> payload = new LinkedHashMap<>();
                if (image.mediaId() != null && !image.mediaId().isBlank()) {
                    payload.put("media_id", image.mediaId());
                } else if (image.picUrl() != null && !image.picUrl().isBlank()) {
                    payload.put("pic_url", image.picUrl());
                } else {
                    throw new ContactApiException("图片附件需要 media_id 或 pic_url");
                }
                attachments.add(Map.of("msgtype", "image", "image", payload));
            }
        }
        if (hasLink) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", link.title() == null || link.title().isBlank() ? "详情" : link.title());
            payload.put("url", link.url());
            if (link.description() != null && !link.description().isBlank()) {
                payload.put("desc", link.description());
            }
            if (link.picUrl() != null && !link.picUrl().isBlank()) {
                payload.put("picurl", link.picUrl());
            }
            attachments.add(Map.of("msgtype", "link", "link", payload));
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw new ContactApiException("附件最多 " + MAX_ATTACHMENTS + " 个");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_type", "group");
        body.put("chat_id_list", chatIds);
        if (hasText) {
            body.put("text", Map.of("content", text));
        }
        if (!attachments.isEmpty()) {
            body.put("attachments", attachments);
        }
        if (sender != null && !sender.isBlank()) {
            body.put("sender", sender);
        }

        if (properties.isDryRun()) {
            log.info("[dry-run] 跳过客户群群发，目标 {} 个群，附件 {} 个", chatIds.size(), attachments.size());
            return new GroupSendResult("dry-run-msg", List.of(), true,
                    "演练模式：任务未真正创建。关闭 wecom.bridge.dry-run 后才会发出。");
        }

        JsonNode response = client.post("/cgi-bin/externalcontact/add_msg_template", body);
        List<String> failList = new ArrayList<>();
        JsonNode fails = response.path("fail_list");
        if (fails.isArray()) {
            fails.forEach(node -> failList.add(node.asText("")));
        }
        return new GroupSendResult(response.path("msgid").asText(""), List.copyOf(failList), false,
                "任务已创建。按官方规则，群主需在企业微信里确认后才会真正发到群内。");
    }

    /** 上传图片素材，返回 media_id。 */
    public String uploadImage(String fileName, String contentType, byte[] content) {
        client.requireConfigured();
        return client.uploadMedia("image", fileName, contentType, content);
    }

    public record GroupChat(String chatId, String name, int memberCount) {
    }

    public record ImageAttachment(String mediaId, String picUrl) {
    }

    public record LinkAttachment(String title, String url, String description, String picUrl) {
    }

    public record GroupSendResult(String msgId, List<String> failList, boolean dryRun, String note) {
    }
}

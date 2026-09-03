package com.wecom.bridge.web;

import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.contact.ContactApiException;
import com.wecom.bridge.contact.GroupMessageService;
import com.wecom.bridge.contact.MomentModels;
import com.wecom.bridge.contact.MomentStatsService;
import com.wecom.bridge.contact.MomentsService;
import com.wecom.bridge.service.PreflightService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营台接口：上线自检、朋友圈发表与互动数据、客户群群发。
 *
 * <p>这三项都是企业微信侧的官方能力；个人微信渠道插件只声明支持一对一私聊，
 * 不提供朋友圈与群聊，因此这里不提供个人微信的对应入口。</p>
 */
@RestController
@RequestMapping("/api/ops")
public class OpsApiController {

    private final BridgeProperties properties;
    private final PreflightService preflightService;
    private final MomentsService momentsService;
    private final MomentStatsService momentStatsService;
    private final GroupMessageService groupMessageService;

    public OpsApiController(BridgeProperties properties,
                            PreflightService preflightService,
                            MomentsService momentsService,
                            MomentStatsService momentStatsService,
                            GroupMessageService groupMessageService) {
        this.properties = properties;
        this.preflightService = preflightService;
        this.momentsService = momentsService;
        this.momentStatsService = momentStatsService;
        this.groupMessageService = groupMessageService;
    }

    /* —— 自检 —— */

    @GetMapping("/preflight")
    public Map<String, Object> preflight(@RequestParam(defaultValue = "true") boolean probe) {
        return preflightService.run(probe);
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        Map<String, Object> wechat = new LinkedHashMap<>();
        wechat.put("direct_message", true);
        wechat.put("media", true);
        wechat.put("group_message", false);
        wechat.put("moments", false);
        wechat.put("note", "OpenClaw 微信渠道插件只声明支持一对一私聊；群聊与朋友圈无官方接口");

        Map<String, Object> wecom = new LinkedHashMap<>();
        wecom.put("direct_message", true);
        wecom.put("group_message", groupMessageService.configured());
        wecom.put("moments", momentsService.configured());
        wecom.put("moment_stats", momentsService.configured());
        wecom.put("note", "群发与朋友圈都是「创建任务 + 成员在企微客户端确认」，接口不会直接发出");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dry_run", properties.isDryRun());
        result.put("wechat", wechat);
        result.put("wecom", wecom);
        return result;
    }

    /* —— 朋友圈 —— */

    @PostMapping("/moments/media")
    public Map<String, Object> uploadMomentImage(@RequestPart("file") MultipartFile file) throws IOException {
        String mediaId = groupMessageService.uploadImage(
                file.getOriginalFilename() == null ? "image.jpg" : file.getOriginalFilename(),
                file.getContentType() == null ? "image/jpeg" : file.getContentType(),
                file.getBytes());
        return Map.of("ok", true, "media_id", mediaId);
    }

    @PostMapping("/moments/tasks")
    public MomentModels.MomentTaskCreated createMoment(@RequestBody MomentRequest request) {
        return momentsService.createTask(new MomentModels.MomentDraft(
                request.text(),
                request.imageMediaIds(),
                request.linkTitle(),
                request.linkUrl(),
                request.linkMediaId(),
                request.senderUserIds(),
                request.senderDepartmentIds(),
                request.customerTagIds()));
    }

    @GetMapping("/moments/tasks/{jobId}")
    public MomentModels.MomentTaskStatus momentTaskStatus(@PathVariable String jobId) {
        return momentsService.taskStatus(jobId);
    }

    @GetMapping("/moments")
    public List<MomentModels.MomentSummary> listMoments(@RequestParam(defaultValue = "7") int days,
                                                        @RequestParam(required = false) String creator,
                                                        @RequestParam(defaultValue = "2") int filterType) {
        long end = Instant.now().getEpochSecond();
        long start = end - Duration.ofDays(Math.min(Math.max(days, 1), 30)).toSeconds();
        return momentsService.listMoments(start, end, creator, filterType);
    }

    /* —— 朋友圈互动数据（点赞 / 评论） —— */

    @GetMapping("/moments/stats")
    public Map<String, Object> momentStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overview", momentStatsService.overview());
        result.put("items", momentStatsService.snapshot());
        return result;
    }

    @PostMapping("/moments/stats/refresh")
    public Map<String, Object> refreshMomentStats() {
        int count = momentStatsService.refresh();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("refreshed", count);
        result.put("overview", momentStatsService.overview());
        return result;
    }

    @PostMapping("/moments/{momentId}/stats/refresh")
    public MomentModels.MomentStats refreshOne(@PathVariable String momentId,
                                               @RequestParam(required = false) String creator) {
        return momentStatsService.refreshOne(momentId, creator);
    }

    /* —— 客户群群发 —— */

    @GetMapping("/groups")
    public List<GroupMessageService.GroupChat> listGroups(@RequestParam(defaultValue = "100") int limit,
                                                          @RequestParam(required = false) List<String> owner) {
        return groupMessageService.listGroupChats(owner, limit);
    }

    @PostMapping("/groups/messages")
    public GroupMessageService.GroupSendResult sendToGroups(@RequestBody GroupMessageRequest request) {
        List<GroupMessageService.ImageAttachment> images = request.images() == null ? List.of()
                : request.images().stream()
                .map(item -> new GroupMessageService.ImageAttachment(item.mediaId(), item.picUrl()))
                .toList();
        GroupMessageService.LinkAttachment link = request.linkUrl() == null || request.linkUrl().isBlank()
                ? null
                : new GroupMessageService.LinkAttachment(
                request.linkTitle(), request.linkUrl(), request.linkDescription(), request.linkPicUrl());

        return groupMessageService.send(request.chatIds(), request.text(), images, link, request.sender());
    }

    /* —— 错误处理 —— */

    @ExceptionHandler(ContactApiException.class)
    public ResponseEntity<Map<String, Object>> handleContactError(ContactApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", e.getMessage());
        body.put("errcode", e.getErrcode());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIoError(IOException e) {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "读取上传文件失败：" + e.getMessage()));
    }

    public record MomentRequest(
            String text,
            List<String> imageMediaIds,
            String linkTitle,
            String linkUrl,
            String linkMediaId,
            List<String> senderUserIds,
            List<Integer> senderDepartmentIds,
            List<String> customerTagIds
    ) {
    }

    public record ImageRequest(String mediaId, String picUrl) {
    }

    public record GroupMessageRequest(
            List<String> chatIds,
            String text,
            List<ImageRequest> images,
            String linkTitle,
            String linkUrl,
            String linkDescription,
            String linkPicUrl,
            String sender
    ) {
    }
}

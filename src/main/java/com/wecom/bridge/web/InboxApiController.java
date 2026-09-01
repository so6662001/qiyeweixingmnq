package com.wecom.bridge.web;

import com.wecom.bridge.archive.ArchiveSdkException;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.service.InboxService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 统一收件箱接口：页面只用这一组接口即可完成「看消息 → 录入回复 → 发出」。
 */
@RestController
@RequestMapping("/api/inbox")
public class InboxApiController {

    private final InboxService inboxService;

    public InboxApiController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return inboxService.status();
    }

    @GetMapping("/conversations")
    public List<InboxConversation> conversations() {
        return inboxService.conversations();
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<InboxMessage> messages(@PathVariable String conversationId,
                                       @RequestParam(defaultValue = "200") int limit) {
        return inboxService.messages(conversationId, limit);
    }

    @PostMapping("/conversations/{conversationId}/read")
    public Map<String, Object> markRead(@PathVariable String conversationId) {
        inboxService.markRead(conversationId);
        return Map.of("ok", true);
    }

    /**
     * 在系统内录入回复，由 OpenClaw 发给对方。
     */
    @PostMapping("/conversations/{conversationId}/reply")
    public InboxMessage reply(@PathVariable String conversationId, @RequestBody ReplyRequest request) {
        return inboxService.reply(conversationId, request == null ? null : request.text());
    }

    /**
     * 补填 OpenClaw 发送目标（企微会话存档身份不能直接当发送目标）。
     */
    @PutMapping("/conversations/{conversationId}/openclaw-target")
    public InboxConversation setTarget(@PathVariable String conversationId,
                                       @RequestBody TargetRequest request) {
        return inboxService.setOpenclawTarget(conversationId, request == null ? null : request.target());
    }

    /**
     * 手动触发一次企微会话存档增量拉取。
     */
    @PostMapping("/archive/pull")
    public Map<String, Object> pullArchive() {
        int imported = inboxService.pullArchive();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("imported", imported);
        return result;
    }

    /**
     * 演示通道：注入一条本地「客户消息」，用于验证实时接收。不触达真实用户。
     */
    @PostMapping("/demo/inbound")
    public ResponseEntity<?> demoInbound(@RequestBody(required = false) DemoInboundRequest request) {
        return inboxService.injectDemoInbound(
                        request == null ? null : request.peerId(),
                        request == null ? null : request.peerName(),
                        request == null ? null : request.text())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of("ok", true, "skipped", "duplicate")));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(error(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(e.getMessage()));
    }

    @ExceptionHandler(ArchiveSdkException.class)
    public ResponseEntity<Map<String, Object>> handleArchiveError(ArchiveSdkException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error(e.getMessage()));
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", message == null ? "未知错误" : message);
        return body;
    }

    public record ReplyRequest(String text) {
    }

    public record TargetRequest(String target) {
    }

    public record DemoInboundRequest(String peerId, String peerName, String text) {
    }
}

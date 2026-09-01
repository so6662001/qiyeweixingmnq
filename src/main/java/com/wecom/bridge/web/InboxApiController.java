package com.wecom.bridge.web;

import com.wecom.bridge.client.WecomApiException;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.service.InboxService;
import com.wecom.bridge.service.WechatKfService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final WechatKfService wechatKfService;

    public InboxApiController(InboxService inboxService, WechatKfService wechatKfService) {
        this.inboxService = inboxService;
        this.wechatKfService = wechatKfService;
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
     * 在系统内录入回复并发给对方。
     */
    @PostMapping("/conversations/{conversationId}/reply")
    public InboxMessage reply(@PathVariable String conversationId, @RequestBody ReplyRequest request) {
        return inboxService.reply(conversationId, request == null ? null : request.text());
    }

    /**
     * 微信客服会话接入人工，便于在本系统直接回复。
     */
    @PostMapping("/conversations/{conversationId}/take-over")
    public Map<String, Object> takeOver(@PathVariable String conversationId,
                                        @RequestBody(required = false) TakeOverRequest request) {
        inboxService.takeOver(conversationId, request == null ? null : request.servicerUserid());
        return Map.of("ok", true);
    }

    /**
     * 兜底：手动触发一次增量拉取。
     */
    @PostMapping("/sync")
    public Map<String, Object> sync() {
        int imported = inboxService.syncWechatKf();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("imported", imported);
        return result;
    }

    @GetMapping("/kf/accounts")
    public List<Map<String, String>> kfAccounts() {
        return wechatKfService.listAccounts();
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

    @DeleteMapping("/conversations/{conversationId}/unread")
    public Map<String, Object> clearUnread(@PathVariable String conversationId) {
        inboxService.markRead(conversationId);
        return Map.of("ok", true);
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

    @ExceptionHandler(WecomApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiError(WecomApiException e) {
        Map<String, Object> body = error(e.getErrmsg());
        body.put("errcode", e.getErrcode());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", message == null ? "未知错误" : message);
        return body;
    }

    public record ReplyRequest(String text) {
    }

    public record TakeOverRequest(String servicerUserid) {
    }

    public record DemoInboundRequest(String peerId, String peerName, String text) {
    }
}

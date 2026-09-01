package com.wecom.bridge.web;

import com.wecom.bridge.openclaw.OpenClawInboundRequest;
import com.wecom.bridge.openclaw.OpenClawInboundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenClaw 插件把个人微信私聊消息回推到这里。
 *
 * <p>插件侧接管会话（不让大模型自动回复），人工在收件箱里录入回复后再由 OpenClaw 发出。</p>
 */
@RestController
@RequestMapping("/api/openclaw")
public class OpenClawInboundController {

    private static final Logger log = LoggerFactory.getLogger(OpenClawInboundController.class);

    private final OpenClawInboundService inboundService;

    public OpenClawInboundController(OpenClawInboundService inboundService) {
        this.inboundService = inboundService;
    }

    @PostMapping("/inbound")
    public ResponseEntity<Map<String, Object>> inbound(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-OpenClaw-Token", required = false) String tokenHeader,
            @RequestBody(required = false) OpenClawInboundRequest request) {

        if (!inboundService.inboundEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(error("OpenClaw 入站未启用：需配置 wecom.bridge.openclaw.enabled 与 inbound-token"));
        }

        String presented = tokenHeader != null && !tokenHeader.isBlank()
                ? tokenHeader.trim()
                : stripBearer(authorization);
        if (!inboundService.authorized(presented)) {
            log.warn("拒绝一次未授权的 OpenClaw 入站请求");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("入站密钥校验失败"));
        }

        try {
            return inboundService.accept(request)
                    .map(message -> {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("ok", true);
                        body.put("message_id", message.getId());
                        body.put("conversation_id", message.getConversationId());
                        return ResponseEntity.ok(body);
                    })
                    .orElseGet(() -> ResponseEntity.ok(Map.of("ok", true, "skipped", true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    private static String stripBearer(String authorization) {
        if (authorization == null) {
            return "";
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", message == null ? "未知错误" : message);
        return body;
    }
}

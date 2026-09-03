package com.wecom.bridge.web;

import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.security.AccessAuthFilter;
import com.wecom.bridge.security.AccessTokenCodec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 口令登录。手机/外网访问收件箱前必须先过这一步。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    private static final Logger log = LoggerFactory.getLogger(AuthApiController.class);

    private final BridgeProperties properties;
    private final AccessTokenCodec tokenCodec;

    public AuthApiController(BridgeProperties properties, AccessTokenCodec tokenCodec) {
        this.properties = properties;
        this.tokenCodec = tokenCodec;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        BridgeProperties.Auth auth = properties.getAuth();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", auth.isEnabled());
        result.put("access_code_configured", auth.hasAccessCode());
        result.put("session_ttl_hours", auth.getSessionTtl().toHours());
        return result;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest request,
                                                     HttpServletRequest httpRequest,
                                                     HttpServletResponse response) {
        BridgeProperties.Auth auth = properties.getAuth();
        if (!auth.isEnabled()) {
            return ResponseEntity.ok(Map.of("ok", true, "note", "鉴权未启用"));
        }
        if (!auth.hasAccessCode()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "ok", false,
                    "error", "服务端未设置访问口令。请配置 WECOM_ACCESS_CODE 后重启，再从手机访问。"));
        }

        String code = request == null ? null : request.code();
        if (!tokenCodec.matchesAccessCode(code)) {
            log.warn("来自 {} 的登录失败", httpRequest.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "口令不正确"));
        }

        writeSessionCookie(response, tokenCodec.issue(), (int) auth.getSessionTtl().toSeconds());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletResponse response) {
        writeSessionCookie(response, "", 0);
        return Map.of("ok", true);
    }

    private void writeSessionCookie(HttpServletResponse response, String value, int maxAgeSeconds) {
        StringBuilder cookie = new StringBuilder();
        cookie.append(AccessAuthFilter.COOKIE_NAME).append('=').append(value)
                .append("; Path=/; HttpOnly; SameSite=Lax; Max-Age=").append(maxAgeSeconds);
        if (properties.getAuth().isCookieSecure()) {
            cookie.append("; Secure");
        }
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public record LoginRequest(String code) {
    }
}

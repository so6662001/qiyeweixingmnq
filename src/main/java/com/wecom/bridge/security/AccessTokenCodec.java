package com.wecom.bridge.security;

import com.wecom.bridge.config.BridgeProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 无状态登录态：token = 过期时间 + HMAC 签名。
 *
 * <p>不在服务端存 session，重启后已登录的手机不用重新输口令；
 * 改口令即让全部旧 token 失效（签名密钥由口令派生）。</p>
 */
@Component
public class AccessTokenCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String KEY_SALT = "wecom-bridge-access-v1";

    private final BridgeProperties properties;

    public AccessTokenCodec(BridgeProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验口令是否正确。使用常量时间比较，避免逐字符试探。
     */
    public boolean matchesAccessCode(String presented) {
        String expected = properties.getAuth().getAccessCode();
        if (expected.isEmpty() || presented == null || presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    public String issue() {
        long expiresAt = System.currentTimeMillis() + properties.getAuth().getSessionTtl().toMillis();
        String payload = Long.toString(expiresAt);
        return encode(payload) + "." + encode(sign(payload));
    }

    /**
     * @return true 表示 token 有效且未过期
     */
    public boolean valid(String token) {
        if (token == null || token.isBlank() || !properties.getAuth().hasAccessCode()) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return false;
        }

        String payload;
        byte[] presentedSignature;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            presentedSignature = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return false;
        }

        if (!MessageDigest.isEqual(sign(payload), presentedSignature)) {
            return false;
        }

        try {
            return Long.parseLong(payload) > System.currentTimeMillis();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest((properties.getAuth().getAccessCode() + KEY_SALT).getBytes(StandardCharsets.UTF_8));
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("签发登录态失败", e);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}

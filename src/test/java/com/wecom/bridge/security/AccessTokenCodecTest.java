package com.wecom.bridge.security;

import com.wecom.bridge.config.BridgeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenCodecTest {

    private BridgeProperties properties;
    private AccessTokenCodec codec;

    @BeforeEach
    void setUp() {
        properties = new BridgeProperties();
        properties.getAuth().setAccessCode("s3cr3t-code");
        codec = new AccessTokenCodec(properties);
    }

    @Test
    void 口令正确才通过() {
        assertThat(codec.matchesAccessCode("s3cr3t-code")).isTrue();
        assertThat(codec.matchesAccessCode("wrong")).isFalse();
        assertThat(codec.matchesAccessCode("")).isFalse();
        assertThat(codec.matchesAccessCode(null)).isFalse();
    }

    @Test
    void 未配置口令时任何口令都不通过() {
        properties.getAuth().setAccessCode("");

        assertThat(codec.matchesAccessCode("anything")).isFalse();
        assertThat(codec.matchesAccessCode("")).isFalse();
    }

    @Test
    void 签发的token能通过校验() {
        assertThat(codec.valid(codec.issue())).isTrue();
    }

    @Test
    void 篡改签名的token不通过() {
        String token = codec.issue();
        String tampered = token.substring(0, token.indexOf('.') + 1) + "AAAA";

        assertThat(codec.valid(tampered)).isFalse();
    }

    @Test
    void 篡改过期时间的token不通过() {
        String token = codec.issue();
        String signature = token.substring(token.indexOf('.'));
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Long.toString(System.currentTimeMillis() + 999_999_999L)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(codec.valid(forgedPayload + signature)).isFalse();
    }

    @Test
    void 过期的token不通过() {
        properties.getAuth().setSessionTtl(Duration.ofMillis(-1));

        assertThat(codec.valid(codec.issue())).isFalse();
    }

    @Test
    void 改口令后旧token立即失效() {
        String token = codec.issue();
        assertThat(codec.valid(token)).isTrue();

        properties.getAuth().setAccessCode("new-code");

        assertThat(codec.valid(token)).isFalse();
    }

    @Test
    void 未配置口令时token一律无效() {
        String token = codec.issue();
        properties.getAuth().setAccessCode("");

        assertThat(codec.valid(token)).isFalse();
    }

    @Test
    void 畸形token不会抛异常() {
        assertThat(codec.valid("")).isFalse();
        assertThat(codec.valid(null)).isFalse();
        assertThat(codec.valid("no-dot")).isFalse();
        assertThat(codec.valid(".")).isFalse();
        assertThat(codec.valid("!!!.!!!")).isFalse();
        assertThat(codec.valid("abc.")).isFalse();
    }
}

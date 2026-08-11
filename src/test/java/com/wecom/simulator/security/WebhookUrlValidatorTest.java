package com.wecom.simulator.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookUrlValidatorTest {

    private final WebhookUrlValidator validator = new WebhookUrlValidator(
            false,
            "localhost,127.0.0.1,::1"
    );

    @Test
    void allowsLoopbackHttp() {
        assertThat(validator.validateAndNormalize("http://127.0.0.1:9000/callback"))
                .isEqualTo("http://127.0.0.1:9000/callback");
        assertThat(validator.validateAndNormalize("http://localhost:9000/hook"))
                .contains("localhost");
    }

    @Test
    void rejectsNonHttpSchemesAndUserInfo() {
        assertThatThrownBy(() -> validator.validateAndNormalize("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("http://user:pass@127.0.0.1/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMetadataAndPublicHostsByDefault() {
        assertThatThrownBy(() -> validator.validateAndNormalize("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("http://example.com/callback"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void privateNetworkRequiresFlag() {
        WebhookUrlValidator strict = new WebhookUrlValidator(false, "localhost");
        assertThatThrownBy(() -> strict.validateAndNormalize("http://10.0.0.8/callback"))
                .isInstanceOf(IllegalArgumentException.class);

        WebhookUrlValidator relaxed = new WebhookUrlValidator(true, "localhost");
        assertThat(relaxed.validateAndNormalize("http://10.0.0.8/callback"))
                .isEqualTo("http://10.0.0.8/callback");
    }
}

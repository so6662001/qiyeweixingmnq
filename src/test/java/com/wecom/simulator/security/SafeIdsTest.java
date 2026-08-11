package com.wecom.simulator.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeIdsTest {

    @Test
    void mediaIdMustBeHex() {
        assertThat(SafeIds.isMediaId("7f2e3bc3ddd14eef9349287e7f868603")).isTrue();
        assertThat(SafeIds.isMediaId("../etc/passwd")).isFalse();
        assertThat(SafeIds.isMediaId("abc")).isFalse();
        assertThat(SafeIds.isMediaId("")).isFalse();
    }

    @Test
    void normalizeAudioExtensionBlocksPathTraversalSuffix() {
        assertThat(SafeIds.normalizeAudioExtension("hello.wav")).isEqualTo("wav");
        assertThat(SafeIds.normalizeAudioExtension("../../tmp/pwned")).isEqualTo("webm");
        assertThat(SafeIds.normalizeAudioExtension("a.wav/../../tmp/pwned")).isEqualTo("webm");
        assertThat(SafeIds.normalizeAudioExtension("voice.EXE")).isEqualTo("webm");
        assertThat(SafeIds.normalizeAudioExtension("rec.WEB M")).isEqualTo("webm");
        assertThat(SafeIds.normalizeAudioExtension("rec.webm")).isEqualTo("webm");
    }

    @Test
    void normalizeImageExtension() {
        assertThat(SafeIds.normalizeImageExtension("promo.PNG")).isEqualTo("png");
        assertThat(SafeIds.normalizeImageExtension("a.png/../../tmp/x")).isEqualTo("jpg");
        assertThat(SafeIds.normalizeImageExtension("file.exe")).isEqualTo("jpg");
        assertThat(SafeIds.isSafeDisplayName("潜在客户甲")).isTrue();
    }

    @Test
    void sanitizeRecognitionTruncates() {
        String longText = "x".repeat(1500);
        assertThat(SafeIds.sanitizeRecognition(longText, "a.wav")).hasSize(1000);
        assertThat(SafeIds.sanitizeRecognition("  hi  ", "a.wav")).isEqualTo("hi");
    }
}

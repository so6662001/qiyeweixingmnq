package com.wecom.simulator.security;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SafeIds {

    private static final Pattern HEX_ID = Pattern.compile("^[a-fA-F0-9]{8,64}$");
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9_\\-.]{1,64}$");
    private static final Set<String> ALLOWED_AUDIO_EXT = Set.of(
            "webm", "wav", "mp3", "ogg", "amr", "m4a", "aac", "opus"
    );

    private SafeIds() {
    }

    public static boolean isMediaId(String mediaId) {
        return mediaId != null && HEX_ID.matcher(mediaId).matches();
    }

    public static boolean isSafeToken(String value) {
        return value != null && SAFE_TOKEN.matcher(value).matches();
    }

    public static String normalizeAudioExtension(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename;
        // 只取纯文件名，避免路径段
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "webm";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_AUDIO_EXT.contains(ext)) {
            return "webm";
        }
        return ext;
    }

    public static String sanitizeRecognition(String recognition, String fallbackName) {
        String value = recognition == null || recognition.isBlank()
                ? "[语音文件] " + (fallbackName == null ? "audio" : fallbackName)
                : recognition.trim();
        if (value.length() > 1000) {
            return value.substring(0, 1000);
        }
        return value;
    }
}

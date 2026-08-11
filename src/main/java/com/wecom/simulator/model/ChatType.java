package com.wecom.simulator.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ChatType {
    PRIVATE("private"),
    GROUP("group");

    private final String value;

    ChatType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static ChatType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return PRIVATE;
        }
        String value = raw.trim().toLowerCase();
        for (ChatType type : values()) {
            if (type.value.equals(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知 chat_type: " + raw);
    }
}

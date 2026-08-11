package com.wecom.simulator.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SenderRole {
    USER("user"),
    BOT("bot"),
    SYSTEM("system");

    private final String value;

    SenderRole(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

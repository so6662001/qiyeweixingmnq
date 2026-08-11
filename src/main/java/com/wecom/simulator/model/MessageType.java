package com.wecom.simulator.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum MessageType {
    TEXT("text"),
    VOICE("voice"),
    EVENT("event");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

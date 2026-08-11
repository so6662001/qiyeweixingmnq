package com.wecom.simulator.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum MomentInteractionType {
    LIKE("like"),
    COMMENT("comment");

    private final String value;

    MomentInteractionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

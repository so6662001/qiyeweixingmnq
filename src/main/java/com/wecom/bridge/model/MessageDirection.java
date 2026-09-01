package com.wecom.bridge.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MessageDirection {

    /** 对方发来的消息。 */
    INBOUND("inbound"),

    /** 我方（本系统内录入）发出的消息。 */
    OUTBOUND("outbound"),

    /** 系统事件，如进入会话、状态变更。 */
    SYSTEM("system");

    private final String value;

    MessageDirection(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static MessageDirection fromValue(String value) {
        for (MessageDirection direction : values()) {
            if (direction.value.equalsIgnoreCase(value)) {
                return direction;
            }
        }
        return INBOUND;
    }
}

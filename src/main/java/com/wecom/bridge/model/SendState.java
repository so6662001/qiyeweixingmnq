package com.wecom.bridge.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SendState {

    /** 入站消息不涉及发送状态。 */
    NONE("none"),

    /** 已提交给官方接口并成功。 */
    SENT("sent"),

    /** 官方接口返回失败，页面上可看到原因。 */
    FAILED("failed"),

    /** 仅记录在本地（演示通道）。 */
    LOCAL("local");

    private final String value;

    SendState(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SendState fromValue(String value) {
        for (SendState state : values()) {
            if (state.value.equalsIgnoreCase(value)) {
                return state;
            }
        }
        return NONE;
    }
}

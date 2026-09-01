package com.wecom.bridge.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 收件箱来源通道。
 */
public enum InboxChannel {

    /**
     * 微信客服：与微信（个人号）用户双向收发的官方通道。
     */
    WECHAT_KF("wechat_kf", "微信用户", true),

    /**
     * 企业微信应用：企业成员与应用之间的消息。
     */
    WECOM_APP("wecom_app", "企微成员", true),

    /**
     * 本地演示通道，数据来自内置模拟器，不触达任何真实用户。
     */
    DEMO("demo", "本地演示", false);

    private final String value;
    private final String displayName;
    private final boolean official;

    InboxChannel(String value, String displayName, boolean official) {
        this.value = value;
        this.displayName = displayName;
        this.official = official;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isOfficial() {
        return official;
    }

    @JsonCreator
    public static InboxChannel fromValue(String value) {
        for (InboxChannel channel : values()) {
            if (channel.value.equalsIgnoreCase(value)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("未知通道: " + value);
    }
}

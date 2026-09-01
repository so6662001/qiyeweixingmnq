package com.wecom.bridge.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 收件箱通道。
 *
 * <p>两个真实通道的入站方式不同，出站统一走 OpenClaw：</p>
 * <ul>
 *   <li>{@link #WECHAT}：个人微信一对一私聊，入站与出站都由 OpenClaw 承担</li>
 *   <li>{@link #WECOM_ARCHIVE}：企业微信入站来自会话内容存档，出站同样走 OpenClaw</li>
 * </ul>
 */
public enum InboxChannel {

    /** 个人微信私聊：OpenClaw 收 + OpenClaw 发。 */
    WECHAT("wechat", "个人微信", "OpenClaw", "OpenClaw", true),

    /** 企业微信：会话内容存档收 + OpenClaw 发。 */
    WECOM_ARCHIVE("wecom_archive", "企业微信", "会话存档", "OpenClaw", true),

    /** 本地演示通道，数据自造，不触达任何真实用户。 */
    DEMO("demo", "本地演示", "本地", "本地", false);

    private final String value;
    private final String displayName;
    private final String inboundVia;
    private final String outboundVia;
    private final String realChannel;

    InboxChannel(String value, String displayName, String inboundVia, String outboundVia, boolean real) {
        this.value = value;
        this.displayName = displayName;
        this.inboundVia = inboundVia;
        this.outboundVia = outboundVia;
        this.realChannel = real ? "real" : "local";
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getInboundVia() {
        return inboundVia;
    }

    public String getOutboundVia() {
        return outboundVia;
    }

    /** 出站是否需要经过 OpenClaw。 */
    public boolean sendsViaOpenClaw() {
        return this == WECHAT || this == WECOM_ARCHIVE;
    }

    public boolean isReal() {
        return "real".equals(realChannel);
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

package com.wecom.bridge.service;

import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;

/**
 * 出站发送能力。真实通道都由 OpenClaw 承担，演示通道只记录本地。
 */
public interface ChannelSender {

    boolean supports(InboxChannel channel);

    /** 是否具备发送条件。未就绪时页面会给出明确提示而不是静默失败。 */
    boolean ready();

    SendOutcome send(InboxConversation conversation, String text);

    record SendOutcome(boolean ok, String outboundMessageId, String errorMessage) {

        public static SendOutcome ok(String outboundMessageId) {
            return new SendOutcome(true, outboundMessageId, null);
        }

        public static SendOutcome failed(String errorMessage) {
            return new SendOutcome(false, null, errorMessage);
        }
    }
}

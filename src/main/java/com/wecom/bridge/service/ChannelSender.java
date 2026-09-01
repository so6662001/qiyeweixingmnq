package com.wecom.bridge.service;

import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;

/**
 * 出站发送能力：每个官方通道实现一份，收件箱按会话所属通道路由。
 */
public interface ChannelSender {

    InboxChannel channel();

    /** 凭据是否齐备。未配置时页面会给出明确提示而不是静默失败。 */
    boolean configured();

    SendOutcome send(InboxConversation conversation, String text);

    record SendOutcome(boolean ok, String officialMsgId, String errorMessage) {

        public static SendOutcome ok(String officialMsgId) {
            return new SendOutcome(true, officialMsgId, null);
        }

        public static SendOutcome failed(String errorMessage) {
            return new SendOutcome(false, null, errorMessage);
        }
    }
}

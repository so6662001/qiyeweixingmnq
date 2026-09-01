package com.wecom.bridge.service;

import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import org.springframework.stereotype.Service;

/**
 * 本地演示通道：不连接任何外部服务，仅用于在没有企业凭据时验证「实时收到 → 页面内回复」链路。
 */
@Service
public class DemoChannelSender implements ChannelSender {

    @Override
    public InboxChannel channel() {
        return InboxChannel.DEMO;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public SendOutcome send(InboxConversation conversation, String text) {
        return SendOutcome.ok("");
    }
}

package com.wecom.bridge.service;

import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.MessageDirection;
import com.wecom.bridge.store.InboxStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 没有真实凭据时，预置一条演示会话，便于先验证「实时接收 → 页面内回复」的完整链路。
 */
@Component
public class DemoInboxSeeder implements ApplicationRunner {

    private static final String DEMO_PEER = "demo-customer";

    private final BridgeProperties properties;
    private final InboxStore store;
    private final InboxRecorder recorder;

    public DemoInboxSeeder(BridgeProperties properties, InboxStore store, InboxRecorder recorder) {
        this.properties = properties;
        this.store = store;
        this.recorder = recorder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isDemoInbox()) {
            return;
        }
        String conversationId = InboxConversation.buildId(InboxChannel.DEMO, DEMO_PEER);
        if (store.findConversation(conversationId).isPresent()) {
            return;
        }

        recorder.record(InboxRecorder.InboundRecord
                .builder(InboxChannel.DEMO, DEMO_PEER)
                .messageId("demo-seed-1")
                .peerName("演示客户")
                .direction(MessageDirection.SYSTEM)
                .msgtype("event")
                .content("这是本地演示会话，消息不会发往任何真实用户。配置 OpenClaw 与会话存档后，真实会话会出现在上方。")
                .createTime(System.currentTimeMillis())
                .build());

        recorder.record(InboxRecorder.InboundRecord
                .builder(InboxChannel.DEMO, DEMO_PEER)
                .messageId("demo-seed-2")
                .peerName("演示客户")
                .direction(MessageDirection.INBOUND)
                .msgtype("text")
                .content("你好，方管 50*100*2.75 有现货吗？")
                .createTime(System.currentTimeMillis())
                .build());

        store.markRead(conversationId);
    }
}

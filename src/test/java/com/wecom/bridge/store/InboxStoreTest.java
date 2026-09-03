package com.wecom.bridge.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.model.MessageDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InboxStoreTest {

    @Test
    void 重启后可恢复会话消息与游标(@TempDir Path tempDir) {
        BridgeProperties properties = properties(tempDir);
        ObjectMapper objectMapper = new ObjectMapper();

        InboxStore first = new InboxStore(properties, objectMapper);
        first.start();
        InboxConversation conversation = first.upsertConversation(InboxChannel.WECHAT, "wm-user-1");
        conversation.setPeerName("李工");
        conversation.setOpenclawTarget("o9cq808_abc@im.wechat");
        first.append(message(conversation.getId(), "msg-1", "方管有现货吗？"));
        first.saveCursor("wecom_archive_seq", "205");
        first.stop();

        assertThat(Files.exists(tempDir.resolve("inbox.json"))).isTrue();

        InboxStore second = new InboxStore(properties, objectMapper);
        second.start();
        try {
            assertThat(second.listConversations()).hasSize(1);
            InboxConversation restored = second.findConversation(conversation.getId()).orElseThrow();
            assertThat(restored.getPeerName()).isEqualTo("李工");
            assertThat(restored.getOpenclawTarget()).isEqualTo("o9cq808_abc@im.wechat");
            assertThat(second.listMessages(conversation.getId(), 10)).hasSize(1);
            assertThat(second.cursor("wecom_archive_seq")).isEqualTo("205");
            // 恢复后仍能去重
            assertThat(second.markSeen("msg-1")).isFalse();
        } finally {
            second.stop();
        }
    }

    @Test
    void 超过上限时淘汰最旧消息(@TempDir Path tempDir) {
        BridgeProperties properties = properties(tempDir);
        properties.setMaxMessages(3);
        InboxStore store = new InboxStore(properties, new ObjectMapper());

        InboxConversation conversation = store.upsertConversation(InboxChannel.DEMO, "demo-1");
        for (int i = 1; i <= 5; i++) {
            InboxMessage message = message(conversation.getId(), "msg-" + i, "第 " + i + " 条");
            message.setCreateTime(1_700_000_000_000L + i);
            store.append(message);
        }

        assertThat(store.listMessages(conversation.getId(), 100))
                .hasSize(3)
                .extracting(InboxMessage::getContent)
                .containsExactly("第 3 条", "第 4 条", "第 5 条");
    }

    @Test
    void 未读计数只统计入站消息(@TempDir Path tempDir) {
        InboxStore store = new InboxStore(properties(tempDir), new ObjectMapper());
        InboxConversation conversation = store.upsertConversation(InboxChannel.DEMO, "demo-1");

        store.append(message(conversation.getId(), "in-1", "你好"));
        InboxMessage outbound = message(conversation.getId(), "out-1", "您好");
        outbound.setDirection(MessageDirection.OUTBOUND);
        store.append(outbound);

        assertThat(store.totalUnread()).isEqualTo(1);
        store.markRead(conversation.getId());
        assertThat(store.totalUnread()).isZero();
    }

    private static BridgeProperties properties(Path tempDir) {
        BridgeProperties properties = new BridgeProperties();
        properties.setDataDir(tempDir.toString());
        return properties;
    }

    private static InboxMessage message(String conversationId, String id, String content) {
        InboxMessage message = new InboxMessage();
        message.setId(id);
        message.setChannel(InboxChannel.WECHAT);
        message.setConversationId(conversationId);
        message.setDirection(MessageDirection.INBOUND);
        message.setMsgtype("text");
        message.setContent(content);
        message.setCreateTime(System.currentTimeMillis());
        return message;
    }
}

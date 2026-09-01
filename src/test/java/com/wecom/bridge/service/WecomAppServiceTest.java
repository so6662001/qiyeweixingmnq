package com.wecom.bridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.client.WecomApiClient;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.model.MessageDirection;
import com.wecom.bridge.store.InboxStore;
import com.wecom.bridge.support.FakeHttpTransport;
import com.wecom.bridge.web.InboxRealtimeHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WecomAppServiceTest {

    private BridgeProperties properties;
    private FakeHttpTransport transport;
    private InboxStore store;
    private WecomAppService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ObjectMapper objectMapper = new ObjectMapper();

        properties = new BridgeProperties();
        properties.setEnabled(true);
        properties.setCorpId("ww-corp-1");
        properties.setDataDir(tempDir.toString());
        properties.getApp().setEnabled(true);
        properties.getApp().setAgentId(1000002L);
        properties.getApp().setSecret("app-secret");
        properties.getApp().setCallbackToken("token");
        properties.getApp().setCallbackAesKey("jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C");

        transport = new FakeHttpTransport();
        WecomApiClient apiClient = new WecomApiClient(properties, transport, objectMapper);
        store = new InboxStore(properties, objectMapper);
        InboxRecorder recorder = new InboxRecorder(store, new InboxRealtimeHub(objectMapper));
        service = new WecomAppService(properties, apiClient, recorder);
    }

    @Test
    void 成员文字消息入库并补齐姓名() {
        transport.respond("/cgi-bin/user/get", "{\"errcode\":0,\"errmsg\":\"ok\",\"name\":\"黄好庭\"}");

        boolean imported = service.handleCallback(Map.of(
                "ToUserName", "ww-corp-1",
                "FromUserName", "huanghaoting",
                "CreateTime", "1756000000",
                "MsgType", "text",
                "Content", "客户催报价了",
                "MsgId", "app-msg-1",
                "AgentID", "1000002"));

        assertThat(imported).isTrue();

        String conversationId = InboxConversation.buildId(InboxChannel.WECOM_APP, "huanghaoting");
        InboxConversation conversation = store.findConversation(conversationId).orElseThrow();
        assertThat(conversation.getPeerName()).isEqualTo("黄好庭");

        List<InboxMessage> messages = store.listMessages(conversationId, 10);
        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.getContent()).isEqualTo("客户催报价了");
            assertThat(message.getDirection()).isEqualTo(MessageDirection.INBOUND);
            assertThat(message.getCreateTime()).isEqualTo(1756000000L * 1000L);
        });
    }

    @Test
    void 通讯录无权限时降级为UserID() {
        transport.respond("/cgi-bin/user/get", "{\"errcode\":60011,\"errmsg\":\"no privilege\"}");

        service.handleCallback(Map.of(
                "FromUserName", "someone",
                "CreateTime", "1756000000",
                "MsgType", "text",
                "Content", "你好",
                "MsgId", "app-msg-2"));

        InboxConversation conversation = store
                .findConversation(InboxConversation.buildId(InboxChannel.WECOM_APP, "someone")).orElseThrow();
        assertThat(conversation.displayName()).isEqualTo("someone");
    }

    @Test
    void 重复MsgId只入库一次() {
        transport.respond("/cgi-bin/user/get", "{\"errcode\":0,\"errmsg\":\"ok\",\"name\":\"黄好庭\"}");
        Map<String, String> fields = Map.of(
                "FromUserName", "huanghaoting",
                "CreateTime", "1756000000",
                "MsgType", "text",
                "Content", "重复消息",
                "MsgId", "same-id");

        assertThat(service.handleCallback(fields)).isTrue();
        assertThat(service.handleCallback(fields)).isFalse();
    }

    @Test
    void 发送成功时带上agentid() {
        transport.respond("/cgi-bin/message/send", "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"official-1\"}");
        InboxConversation conversation = store.upsertConversation(
                InboxChannel.WECOM_APP, "huanghaoting", "黄好庭", null);

        ChannelSender.SendOutcome outcome = service.send(conversation, "已核价");

        assertThat(outcome.ok()).isTrue();
        assertThat(transport.lastCallTo("/cgi-bin/message/send").body())
                .contains("\"agentid\":1000002")
                .contains("\"touser\":\"huanghaoting\"");
    }

    @Test
    void 成员不在可见范围时给出原因() {
        transport.respond("/cgi-bin/message/send",
                "{\"errcode\":0,\"errmsg\":\"ok\",\"invaliduser\":\"someone\",\"msgid\":\"x\"}");
        InboxConversation conversation = store.upsertConversation(
                InboxChannel.WECOM_APP, "someone", null, null);

        ChannelSender.SendOutcome outcome = service.send(conversation, "你好");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errorMessage()).contains("可见范围");
    }
}

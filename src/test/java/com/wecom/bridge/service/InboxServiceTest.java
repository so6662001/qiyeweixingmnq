package com.wecom.bridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.client.WecomApiClient;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.model.SendState;
import com.wecom.bridge.store.InboxStore;
import com.wecom.bridge.support.FakeHttpTransport;
import com.wecom.bridge.web.InboxRealtimeHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboxServiceTest {

    private BridgeProperties properties;
    private FakeHttpTransport transport;
    private InboxStore store;
    private InboxService inboxService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ObjectMapper objectMapper = new ObjectMapper();

        properties = new BridgeProperties();
        properties.setEnabled(true);
        properties.setCorpId("ww-corp-1");
        properties.setDataDir(tempDir.toString());
        properties.getKf().setEnabled(true);
        properties.getKf().setSecret("kf-secret");
        properties.getKf().setCallbackToken("token");
        properties.getKf().setCallbackAesKey("jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C");
        properties.getApp().setEnabled(true);
        properties.getApp().setAgentId(1000002L);
        properties.getApp().setSecret("app-secret");
        properties.getApp().setCallbackToken("token");
        properties.getApp().setCallbackAesKey("jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C");

        transport = new FakeHttpTransport();
        WecomApiClient apiClient = new WecomApiClient(properties, transport, objectMapper);
        store = new InboxStore(properties, objectMapper);
        InboxRealtimeHub hub = new InboxRealtimeHub(objectMapper);
        InboxRecorder recorder = new InboxRecorder(store, hub);

        WechatKfService kfService = new WechatKfService(properties, apiClient, recorder, store);
        WecomAppService appService = new WecomAppService(properties, apiClient, recorder);
        DemoChannelSender demoSender = new DemoChannelSender();

        inboxService = new InboxService(store, recorder, properties, kfService,
                List.of(kfService, appService, demoSender));
    }

    @Test
    void 微信客服会话回复走send_msg并标记已发送() {
        transport.respond("/cgi-bin/kf/send_msg", "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"official-1\"}");
        InboxConversation conversation = kfConversation();

        InboxMessage message = inboxService.reply(conversation.getId(), "  已核价，稍后发您报价单  ");

        assertThat(message.getSendState()).isEqualTo(SendState.SENT);
        assertThat(message.getContent()).isEqualTo("已核价，稍后发您报价单");
        assertThat(transport.callsTo("/cgi-bin/kf/send_msg")).hasSize(1);
        assertThat(store.listMessages(conversation.getId(), 10)).hasSize(1);
    }

    @Test
    void 企微成员会话回复走message_send() {
        transport.respond("/cgi-bin/message/send", "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"official-2\"}");
        InboxConversation conversation = store.upsertConversation(
                InboxChannel.WECOM_APP, "huanghaoting", "黄好庭", null);

        InboxMessage message = inboxService.reply(conversation.getId(), "收到，我去核价");

        assertThat(message.getSendState()).isEqualTo(SendState.SENT);
        String body = transport.lastCallTo("/cgi-bin/message/send").body();
        assertThat(body)
                .contains("\"touser\":\"huanghaoting\"")
                .contains("\"agentid\":1000002");
    }

    @Test
    void 发送失败时把原因写回消息() {
        transport.respond("/cgi-bin/kf/send_msg", "{\"errcode\":95004,\"errmsg\":\"not allow\"}");
        InboxConversation conversation = kfConversation();

        InboxMessage message = inboxService.reply(conversation.getId(), "在吗");

        assertThat(message.getSendState()).isEqualTo(SendState.FAILED);
        assertThat(message.getErrorMessage()).contains("48 小时");
    }

    @Test
    void 演示通道只记录本地不发外部请求() {
        inboxService.injectDemoInbound("demo-1", "演示客户", "你好");
        String conversationId = InboxConversation.buildId(InboxChannel.DEMO, "demo-1");

        InboxMessage message = inboxService.reply(conversationId, "这是演示回复");

        assertThat(message.getSendState()).isEqualTo(SendState.LOCAL);
        assertThat(transport.calls()).isEmpty();
    }

    @Test
    void 空内容与超长内容被拒绝() {
        InboxConversation conversation = kfConversation();

        assertThatThrownBy(() -> inboxService.reply(conversation.getId(), "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");

        assertThatThrownBy(() -> inboxService.reply(conversation.getId(), "字".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过长");
    }

    @Test
    void 会话不存在时返回明确错误() {
        assertThatThrownBy(() -> inboxService.reply("wechat_kf:not-exist", "你好"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 标记已读会清空未读数() {
        inboxService.injectDemoInbound("demo-2", "演示客户", "你好");
        String conversationId = InboxConversation.buildId(InboxChannel.DEMO, "demo-2");
        assertThat(store.findConversation(conversationId).orElseThrow().getUnread()).isEqualTo(1);

        inboxService.markRead(conversationId);

        assertThat(store.findConversation(conversationId).orElseThrow().getUnread()).isZero();
        assertThat(store.totalUnread()).isZero();
    }

    @Test
    void 状态接口反映配置情况() {
        var status = inboxService.status();

        assertThat(status).containsEntry("enabled", true).containsEntry("corp_id_configured", true);
        assertThat(status.get("wechat_kf")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("configured", true)
                .containsEntry("callback_path", "/callback/wecom/kf");
    }

    private InboxConversation kfConversation() {
        InboxConversation conversation = store.upsertConversation(
                InboxChannel.WECHAT_KF, "wm-user-1", "李工", "wk-open-1");
        conversation.setLastInboundAt(System.currentTimeMillis() - Duration.ofMinutes(3).toMillis());
        return conversation;
    }
}

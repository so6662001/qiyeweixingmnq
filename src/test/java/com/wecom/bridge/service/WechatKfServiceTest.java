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
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WechatKfServiceTest {

    private static final String SYNC_ONE_TEXT = """
            {
              "errcode": 0,
              "errmsg": "ok",
              "next_cursor": "cursor-1",
              "has_more": 0,
              "msg_list": [
                {
                  "msgid": "msg-1",
                  "open_kfid": "wk-open-1",
                  "external_userid": "wm-user-1",
                  "send_time": 1756000000,
                  "origin": 3,
                  "msgtype": "text",
                  "text": { "content": "方管有现货吗？" }
                }
              ]
            }
            """;

    private BridgeProperties properties;
    private FakeHttpTransport transport;
    private InboxStore store;
    private WechatKfService service;

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

        transport = new FakeHttpTransport();
        WecomApiClient apiClient = new WecomApiClient(properties, transport, objectMapper);
        store = new InboxStore(properties, objectMapper);
        InboxRealtimeHub hub = new InboxRealtimeHub(objectMapper);
        InboxRecorder recorder = new InboxRecorder(store, hub);
        service = new WechatKfService(properties, apiClient, recorder, store);
    }

    @Test
    void 拉取客服消息后生成会话与消息并保存游标() {
        transport.respond("/cgi-bin/kf/sync_msg", SYNC_ONE_TEXT)
                .respond("/cgi-bin/kf/customer/batchget", """
                        {"errcode":0,"errmsg":"ok","customer_list":[{"external_userid":"wm-user-1","nickname":"李工"}]}
                        """);

        int imported = service.handleCallbackEvent("event-token", "wk-open-1");

        assertThat(imported).isEqualTo(1);

        String conversationId = InboxConversation.buildId(InboxChannel.WECHAT_KF, "wm-user-1");
        InboxConversation conversation = store.findConversation(conversationId).orElseThrow();
        assertThat(conversation.getOpenKfid()).isEqualTo("wk-open-1");
        assertThat(conversation.getPeerName()).isEqualTo("李工");
        assertThat(conversation.getUnread()).isEqualTo(1);

        List<InboxMessage> messages = store.listMessages(conversationId, 10);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getContent()).isEqualTo("方管有现货吗？");
        assertThat(messages.get(0).getDirection()).isEqualTo(MessageDirection.INBOUND);
        assertThat(messages.get(0).getCreateTime()).isEqualTo(1756000000L * 1000L);

        assertThat(store.cursor("wk-open-1")).isEqualTo("cursor-1");

        // 游标随下一次请求带上
        transport.reset();
        service.syncMessages(null, "wk-open-1");
        assertThat(transport.lastCallTo("/cgi-bin/kf/sync_msg").body()).contains("\"cursor\":\"cursor-1\"");
    }

    @Test
    void 相同msgid不会重复入库() {
        transport.respond("/cgi-bin/kf/sync_msg", SYNC_ONE_TEXT)
                .respond("/cgi-bin/kf/customer/batchget", "{\"errcode\":0,\"errmsg\":\"ok\",\"customer_list\":[]}");

        assertThat(service.syncMessages(null, "wk-open-1")).isEqualTo(1);
        assertThat(service.syncMessages(null, "wk-open-1")).isZero();

        String conversationId = InboxConversation.buildId(InboxChannel.WECHAT_KF, "wm-user-1");
        assertThat(store.listMessages(conversationId, 10)).hasSize(1);
    }

    @Test
    void 接待人员发出的消息记为出站() {
        transport.respond("/cgi-bin/kf/sync_msg", """
                {
                  "errcode": 0, "errmsg": "ok", "next_cursor": "c2", "has_more": 0,
                  "msg_list": [{
                    "msgid": "msg-out-1", "open_kfid": "wk-open-1", "external_userid": "wm-user-1",
                    "send_time": 1756000100, "origin": 5, "servicer_userid": "huanghaoting",
                    "msgtype": "text", "text": { "content": "有现货，我发报价" }
                  }]
                }
                """).respond("/cgi-bin/kf/customer/batchget", "{\"errcode\":0,\"errmsg\":\"ok\",\"customer_list\":[]}");

        service.syncMessages(null, "wk-open-1");

        List<InboxMessage> messages = store.listMessages(
                InboxConversation.buildId(InboxChannel.WECHAT_KF, "wm-user-1"), 10);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getDirection()).isEqualTo(MessageDirection.OUTBOUND);
        assertThat(messages.get(0).getServicerUserid()).isEqualTo("huanghaoting");
    }

    @Test
    void 发送成功时返回官方msgid() {
        transport.respond("/cgi-bin/kf/send_msg", "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"official-1\"}");

        InboxConversation conversation = conversationWithRecentInbound();
        ChannelSender.SendOutcome outcome = service.send(conversation, "已核价，稍后发您报价单");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.officialMsgId()).isEqualTo("official-1");

        String body = transport.lastCallTo("/cgi-bin/kf/send_msg").body();
        assertThat(body)
                .contains("\"touser\":\"wm-user-1\"")
                .contains("\"open_kfid\":\"wk-open-1\"")
                .contains("已核价，稍后发您报价单");
    }

    @Test
    void 超出48小时窗口时本地就拦截并给出可读原因() {
        InboxConversation conversation = conversationWithRecentInbound();
        conversation.setLastInboundAt(System.currentTimeMillis() - Duration.ofHours(50).toMillis());

        ChannelSender.SendOutcome outcome = service.send(conversation, "在吗");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errorMessage()).contains("可回复窗口");
        assertThat(transport.callsTo("/cgi-bin/kf/send_msg")).isEmpty();
    }

    @Test
    void 官方错误码转成中文说明() {
        transport.respond("/cgi-bin/kf/send_msg", "{\"errcode\":95004,\"errmsg\":\"not allow to send msg\"}");

        ChannelSender.SendOutcome outcome = service.send(conversationWithRecentInbound(), "在吗");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errorMessage()).contains("48 小时");
    }

    @Test
    void 未配置时不会发起任何请求() {
        properties.getKf().setSecret("");
        ChannelSender.SendOutcome outcome = service.send(conversationWithRecentInbound(), "在吗");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errorMessage()).contains("未配置");
        assertThat(transport.calls()).isEmpty();
    }

    @Test
    void 开启自动接管时先转人工再发送() {
        properties.getKf().setServicerUserid("huanghaoting");
        properties.getKf().setAutoTakeOver(true);
        transport.respond("/cgi-bin/kf/service_state/trans", "{\"errcode\":0,\"errmsg\":\"ok\"}")
                .respond("/cgi-bin/kf/send_msg", "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"official-2\"}");

        InboxConversation conversation = conversationWithRecentInbound();
        assertThat(service.send(conversation, "您好").ok()).isTrue();

        assertThat(transport.callsTo("/cgi-bin/kf/service_state/trans")).hasSize(1);
        assertThat(transport.lastCallTo("/cgi-bin/kf/service_state/trans").body())
                .contains("\"service_state\":3")
                .contains("huanghaoting");
        assertThat(conversation.getServiceState()).isEqualTo(3);
    }

    @Test
    void 会话事件记为系统消息() {
        transport.respond("/cgi-bin/kf/sync_msg", """
                {
                  "errcode": 0, "errmsg": "ok", "next_cursor": "c3", "has_more": 0,
                  "msg_list": [{
                    "msgid": "msg-evt-1", "open_kfid": "wk-open-1", "external_userid": "wm-user-1",
                    "send_time": 1756000200, "origin": 4, "msgtype": "event",
                    "event": { "event_type": "enter_session", "scene": "default" }
                  }]
                }
                """).respond("/cgi-bin/kf/customer/batchget", "{\"errcode\":0,\"errmsg\":\"ok\",\"customer_list\":[]}");

        service.syncMessages(null, "wk-open-1");

        List<InboxMessage> messages = store.listMessages(
                InboxConversation.buildId(InboxChannel.WECHAT_KF, "wm-user-1"), 10);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getDirection()).isEqualTo(MessageDirection.SYSTEM);
        assertThat(messages.get(0).getContent()).contains("进入会话");
    }

    private InboxConversation conversationWithRecentInbound() {
        InboxConversation conversation = store.upsertConversation(
                InboxChannel.WECHAT_KF, "wm-user-1", "李工", "wk-open-1");
        conversation.setLastInboundAt(System.currentTimeMillis() - Duration.ofMinutes(5).toMillis());
        return conversation;
    }
}

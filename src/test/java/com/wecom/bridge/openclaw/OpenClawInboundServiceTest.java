package com.wecom.bridge.openclaw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.model.MessageDirection;
import com.wecom.bridge.service.InboxRecorder;
import com.wecom.bridge.store.InboxStore;
import com.wecom.bridge.web.InboxRealtimeHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenClawInboundServiceTest {

    private BridgeProperties properties;
    private InboxStore store;
    private OpenClawInboundService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ObjectMapper objectMapper = new ObjectMapper();
        properties = new BridgeProperties();
        properties.setEnabled(true);
        properties.setDataDir(tempDir.toString());
        properties.getOpenclaw().setEnabled(true);
        properties.getOpenclaw().setInboundToken("secret-token");

        store = new InboxStore(properties, objectMapper);
        InboxRecorder recorder = new InboxRecorder(store, new InboxRealtimeHub(objectMapper));
        service = new OpenClawInboundService(properties, recorder);
    }

    @Test
    void 密钥一致才放行() {
        assertThat(service.authorized("secret-token")).isTrue();
        assertThat(service.authorized("wrong")).isFalse();
        assertThat(service.authorized(null)).isFalse();
        assertThat(service.authorized("")).isFalse();
    }

    @Test
    void 未配置密钥时拒绝一切入站() {
        properties.getOpenclaw().setInboundToken("");

        assertThat(service.inboundEnabled()).isFalse();
        assertThat(service.authorized("anything")).isFalse();
    }

    @Test
    void 私聊消息入库并把对端作为发送目标() {
        OpenClawInboundRequest request = request("wx-1", "o9cq808_abc@im.wechat", "李工", "方管有现货吗？");

        InboxMessage message = service.accept(request).orElseThrow();

        assertThat(message.getChannel()).isEqualTo(InboxChannel.WECHAT);
        assertThat(message.getDirection()).isEqualTo(MessageDirection.INBOUND);
        assertThat(message.getContent()).isEqualTo("方管有现货吗？");

        InboxConversation conversation = store
                .findConversation(InboxConversation.buildId(InboxChannel.WECHAT, "o9cq808_abc@im.wechat"))
                .orElseThrow();
        assertThat(conversation.getPeerName()).isEqualTo("李工");
        assertThat(conversation.getOpenclawTarget()).isEqualTo("o9cq808_abc@im.wechat");
        assertThat(conversation.getUnread()).isEqualTo(1);
    }

    @Test
    void 群聊消息被跳过() {
        OpenClawInboundRequest request = request("wx-group-1", "room-1", "群", "群里发的");
        request.setGroup(true);

        assertThat(service.accept(request)).isEmpty();
        assertThat(store.listConversations()).isEmpty();
    }

    @Test
    void 重复消息只入库一次() {
        OpenClawInboundRequest first = request("wx-dup", "peer-1", "李工", "重复");
        OpenClawInboundRequest second = request("wx-dup", "peer-1", "李工", "重复");

        assertThat(service.accept(first)).isPresent();
        assertThat(service.accept(second)).isEmpty();

        List<InboxMessage> messages = store.listMessages(
                InboxConversation.buildId(InboxChannel.WECHAT, "peer-1"), 10);
        assertThat(messages).hasSize(1);
    }

    @Test
    void 本方发出的镜像消息记为出站() {
        OpenClawInboundRequest request = request("wx-out-1", "peer-1", "李工", "我在手机上回的");
        request.setOutbound(true);

        InboxMessage message = service.accept(request).orElseThrow();

        assertThat(message.getDirection()).isEqualTo(MessageDirection.OUTBOUND);
        // 出站消息不计未读
        assertThat(store.totalUnread()).isZero();
    }

    @Test
    void 秒级时间戳被换算为毫秒() {
        OpenClawInboundRequest request = request("wx-ts", "peer-1", "李工", "你好");
        request.setTimestamp(1_756_000_000L);

        InboxMessage message = service.accept(request).orElseThrow();

        assertThat(message.getCreateTime()).isEqualTo(1_756_000_000_000L);
    }

    @Test
    void 毫秒时间戳原样保留() {
        OpenClawInboundRequest request = request("wx-ts2", "peer-1", "李工", "你好");
        request.setTimestamp(1_756_000_000_123L);

        assertThat(service.accept(request).orElseThrow().getCreateTime()).isEqualTo(1_756_000_000_123L);
    }

    @Test
    void 缺少对端时报错() {
        OpenClawInboundRequest request = request("wx-x", "  ", "李工", "你好");

        assertThatThrownBy(() -> service.accept(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("peer_id");
    }

    @Test
    void 请求体为空时报错() {
        assertThatThrownBy(() -> service.accept(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OpenClawInboundRequest request(String messageId, String peerId, String peerName, String content) {
        OpenClawInboundRequest request = new OpenClawInboundRequest();
        request.setChannel("openclaw-weixin");
        request.setMessageId(messageId);
        request.setPeerId(peerId);
        request.setPeerName(peerName);
        request.setMsgtype("text");
        request.setContent(content);
        request.setTimestamp(System.currentTimeMillis());
        return request;
    }
}

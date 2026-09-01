package com.wecom.bridge.service;

import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.model.MessageDirection;
import com.wecom.bridge.store.InboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 统一收件箱：页面所有读写都经过这里，按会话所属通道路由到对应官方接口。
 */
@Service
public class InboxService {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    /** 官方文本消息长度上限约 2048 字节，这里保守限制字符数并给出可读提示。 */
    private static final int MAX_TEXT_LENGTH = 2000;

    private final InboxStore store;
    private final InboxRecorder recorder;
    private final BridgeProperties properties;
    private final WechatKfService wechatKfService;
    private final Map<InboxChannel, ChannelSender> senders = new EnumMap<>(InboxChannel.class);

    public InboxService(InboxStore store,
                        InboxRecorder recorder,
                        BridgeProperties properties,
                        WechatKfService wechatKfService,
                        List<ChannelSender> channelSenders) {
        this.store = store;
        this.recorder = recorder;
        this.properties = properties;
        this.wechatKfService = wechatKfService;
        channelSenders.forEach(sender -> senders.put(sender.channel(), sender));
    }

    public List<InboxConversation> conversations() {
        return store.listConversations();
    }

    public List<InboxMessage> messages(String conversationId, int limit) {
        return store.listMessages(conversationId, limit);
    }

    public InboxConversation conversation(String conversationId) {
        return store.findConversation(conversationId)
                .orElseThrow(() -> new NoSuchElementException("会话不存在: " + conversationId));
    }

    public void markRead(String conversationId) {
        store.markRead(conversationId);
        store.findConversation(conversationId).ifPresent(recorder::publishConversation);
    }

    /**
     * 在系统内录入回复并发往对方。发送结果写回同一条消息，页面通过 WebSocket 立即看到。
     */
    public InboxMessage reply(String conversationId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("回复内容不能为空");
        }
        String trimmed = text.strip();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("回复内容过长，请控制在 " + MAX_TEXT_LENGTH + " 字以内");
        }

        InboxConversation conversation = conversation(conversationId);
        ChannelSender sender = senders.get(conversation.getChannel());
        String servicer = properties.getKf().getServicerUserid();
        InboxMessage message = recorder.recordOutbound(conversation, "text", trimmed, servicer);

        if (sender == null) {
            recorder.markFailed(conversation, message, "没有可用的发送通道: " + conversation.getChannel().getValue());
            return message;
        }

        if (conversation.getChannel() == InboxChannel.DEMO) {
            recorder.markLocal(conversation, message);
            return message;
        }

        ChannelSender.SendOutcome outcome;
        try {
            outcome = sender.send(conversation, trimmed);
        } catch (RuntimeException e) {
            log.warn("发送失败: {}", e.getMessage());
            recorder.markFailed(conversation, message, "发送异常：" + e.getMessage());
            return message;
        }

        if (outcome.ok()) {
            recorder.markSent(conversation, message, outcome.officialMsgId());
        } else {
            recorder.markFailed(conversation, message, outcome.errorMessage());
        }
        return message;
    }

    /**
     * 手动触发一次微信客服增量拉取（回调偶发丢失时的兜底）。
     */
    public int syncWechatKf() {
        if (!wechatKfService.configured()) {
            throw new IllegalStateException("微信客服通道未配置，无法同步");
        }
        return wechatKfService.syncMessages(null, null);
    }

    public void takeOver(String conversationId, String servicerUserid) {
        InboxConversation conversation = conversation(conversationId);
        if (conversation.getChannel() != InboxChannel.WECHAT_KF) {
            throw new IllegalArgumentException("只有微信客服会话需要接入人工");
        }
        String servicer = servicerUserid == null || servicerUserid.isBlank()
                ? properties.getKf().getServicerUserid()
                : servicerUserid.trim();
        if (servicer.isBlank()) {
            throw new IllegalArgumentException("未配置接待人员 UserID（wecom.bridge.kf.servicer-userid）");
        }
        wechatKfService.transferToHuman(conversation, servicer);
    }

    /**
     * 演示通道：本地注入一条「客户消息」，用于验证实时接收链路。不触达任何真实用户。
     */
    public Optional<InboxMessage> injectDemoInbound(String peerId, String peerName, String text) {
        String id = peerId == null || peerId.isBlank() ? "demo-customer" : peerId.trim();
        return recorder.recordInbound(InboxRecorder.InboundRecord
                .builder(InboxChannel.DEMO, id)
                .messageId("demo-" + System.nanoTime())
                .peerName(peerName == null || peerName.isBlank() ? "演示客户" : peerName)
                .direction(MessageDirection.INBOUND)
                .msgtype("text")
                .content(text == null || text.isBlank() ? "这是一条演示消息" : text)
                .createTime(System.currentTimeMillis())
                .build());
    }

    /**
     * 页面顶部状态条所需的配置与连通性信息。
     */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("corp_id_configured", !properties.getCorpId().isBlank());
        status.put("demo_inbox", properties.isDemoInbox());
        status.put("total_unread", store.totalUnread());
        status.put("conversation_count", store.listConversations().size());

        Map<String, Object> kf = new LinkedHashMap<>();
        kf.put("enabled", properties.getKf().isEnabled());
        kf.put("configured", properties.getKf().isConfigured());
        kf.put("servicer_configured", !properties.getKf().getServicerUserid().isBlank());
        kf.put("auto_take_over", properties.getKf().isAutoTakeOver());
        kf.put("reply_window_hours", properties.getKf().getReplyWindow().toHours());
        kf.put("callback_path", "/callback/wecom/kf");
        status.put("wechat_kf", kf);

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("enabled", properties.getApp().isEnabled());
        app.put("configured", properties.getApp().isConfigured());
        app.put("agent_id", properties.getApp().getAgentId());
        app.put("callback_path", "/callback/wecom/app");
        status.put("wecom_app", app);

        return status;
    }
}

package com.wecom.bridge.service;

import com.wecom.bridge.archive.SessionArchivePoller;
import com.wecom.bridge.archive.SessionArchiveService;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.model.MessageDirection;
import com.wecom.bridge.openclaw.OpenClawGateway;
import com.wecom.bridge.openclaw.OpenClawHealthService;
import com.wecom.bridge.store.InboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 统一收件箱：页面所有读写都经过这里。
 *
 * <p>入站有两条来源（OpenClaw 个人微信、企微会话存档），出站统一由 OpenClaw 发出。</p>
 */
@Service
public class InboxService {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    /** 文本长度上限，避免把超长内容丢给外部命令。 */
    private static final int MAX_TEXT_LENGTH = 2000;

    private final InboxStore store;
    private final InboxRecorder recorder;
    private final BridgeProperties properties;
    private final SessionArchiveService archiveService;
    private final SessionArchivePoller archivePoller;
    private final OpenClawGateway openClawGateway;
    private final OpenClawHealthService openClawHealth;
    private final OpenClawSender openClawSender;
    private final List<ChannelSender> senders;

    public InboxService(InboxStore store,
                        InboxRecorder recorder,
                        BridgeProperties properties,
                        SessionArchiveService archiveService,
                        SessionArchivePoller archivePoller,
                        OpenClawGateway openClawGateway,
                        OpenClawHealthService openClawHealth,
                        OpenClawSender openClawSender,
                        List<ChannelSender> senders) {
        this.store = store;
        this.recorder = recorder;
        this.properties = properties;
        this.archiveService = archiveService;
        this.archivePoller = archivePoller;
        this.openClawGateway = openClawGateway;
        this.openClawHealth = openClawHealth;
        this.openClawSender = openClawSender;
        this.senders = senders;
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
     * 在系统内录入回复并通过 OpenClaw 发给对方。发送结果写回同一条消息。
     */
    public InboxMessage reply(String conversationId, String text) {
        return reply(conversationId, text, null);
    }

    /**
     * 录入回复并发出，可附带一个媒体（图片 / 文件，本地路径或 URL）。
     */
    public InboxMessage reply(String conversationId, String text, String mediaUrl) {
        boolean hasMedia = mediaUrl != null && !mediaUrl.isBlank();
        if ((text == null || text.isBlank()) && !hasMedia) {
            throw new IllegalArgumentException("回复内容不能为空");
        }
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("回复内容过长，请控制在 " + MAX_TEXT_LENGTH + " 字以内");
        }

        InboxConversation conversation = conversation(conversationId);
        ChannelSender sender = resolveSender(conversation.getChannel());

        // 发送前把解析出的目标写回会话，页面与失败排查都能看到实际用了哪个目标
        if (conversation.getChannel().sendsViaOpenClaw()) {
            String resolved = openClawSender.resolveTarget(conversation);
            if (resolved != null && !resolved.isBlank() && !resolved.equals(conversation.getOpenclawTarget())) {
                conversation.setOpenclawTarget(resolved);
            }
        }

        InboxMessage message = recorder.recordOutbound(conversation,
                hasMedia && trimmed.isEmpty() ? "[媒体] " + mediaUrl : trimmed);
        if (hasMedia) {
            message.setMsgtype("media");
            message.setMediaId(mediaUrl);
        }

        if (sender == null) {
            recorder.markFailed(conversation, message, "没有可用的发送通道: " + conversation.getChannel().getValue());
            return message;
        }
        if (conversation.getChannel() == InboxChannel.DEMO) {
            recorder.markLocal(conversation, message);
            return message;
        }
        if (!sender.ready()) {
            recorder.markFailed(conversation, message,
                    "OpenClaw 出站未就绪：请确认 openclaw 已安装、微信渠道已登录，并开启 wecom.bridge.openclaw.enabled");
            return message;
        }

        ChannelSender.SendOutcome outcome;
        try {
            outcome = hasMedia && sender instanceof OpenClawSender openClaw
                    ? openClaw.send(conversation, trimmed, mediaUrl)
                    : sender.send(conversation, trimmed);
        } catch (RuntimeException e) {
            log.warn("发送失败: {}", e.getMessage());
            recorder.markFailed(conversation, message, "发送异常：" + e.getMessage());
            return message;
        }

        if (outcome.ok()) {
            recorder.markSent(conversation, message, outcome.outboundMessageId());
        } else {
            recorder.markFailed(conversation, message, outcome.errorMessage());
        }
        return message;
    }

    private ChannelSender resolveSender(InboxChannel channel) {
        return senders.stream().filter(sender -> sender.supports(channel)).findFirst().orElse(null);
    }

    /**
     * 手动触发一次企微会话存档增量拉取。个人微信是插件实时回推，不需要手动同步。
     */
    public int pullArchive() {
        if (!archiveService.configured()) {
            throw new IllegalStateException("企微会话存档未配置，无法拉取");
        }
        return archiveService.pull();
    }

    /**
     * 为会话设置 OpenClaw 发送目标。企微存档身份不能直接当发送目标，需要人工补映射。
     */
    public InboxConversation setOpenclawTarget(String conversationId, String target) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("发送目标不能为空");
        }
        InboxConversation conversation = conversation(conversationId);
        conversation.setOpenclawTarget(target.strip());
        recorder.publishConversation(conversation);
        return conversation;
    }

    /**
     * 演示通道：本地注入一条「客户消息」，用于验证实时接收。不触达任何真实用户。
     */
    public Optional<InboxMessage> injectDemoInbound(String peerId, String peerName, String text) {
        String id = peerId == null || peerId.isBlank() ? "demo-customer" : peerId.trim();
        return recorder.record(InboxRecorder.InboundRecord
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
     * 页面状态条所需的配置与连通性信息。
     */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("demo_inbox", properties.isDemoInbox());
        status.put("total_unread", store.totalUnread());
        status.put("conversation_count", store.listConversations().size());

        BridgeProperties.OpenClaw openclaw = properties.getOpenclaw();
        Map<String, Object> openclawStatus = new LinkedHashMap<>();
        openclawStatus.put("enabled", openclaw.isEnabled());
        openclawStatus.put("outbound_ready", openClawGateway.outboundReady());
        openclawStatus.put("inbound_ready", openclaw.isInboundReady());
        openclawStatus.put("wechat_channel", openclaw.getWechatChannel());
        openclawStatus.put("cli_path", openclaw.getCliPath());
        openclawStatus.put("inbound_path", "/api/openclaw/inbound");
        openclawStatus.put("health", openClawHealth.describe());
        status.put("openclaw", openclawStatus);

        BridgeProperties.Archive archive = properties.getArchive();
        Map<String, Object> archiveStatus = new LinkedHashMap<>();
        archiveStatus.put("enabled", archive.isEnabled());
        archiveStatus.put("configured", archive.isConfigured());
        archiveStatus.put("sdk_ready", archiveService.sdkReady());
        archiveStatus.put("seq", archiveService.currentSeq());
        archiveStatus.put("poll_interval_seconds", archive.getPollInterval().toSeconds());
        archiveStatus.put("include_room_chats", archive.isIncludeRoomChats());
        archiveStatus.put("last_error", archivePoller.lastError());
        status.put("archive", archiveStatus);

        return status;
    }
}

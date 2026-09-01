package com.wecom.bridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.wecom.bridge.client.WecomApiClient;
import com.wecom.bridge.client.WecomApiException;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.MessageDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信自建应用通道：接收企业成员发给应用的消息，并用官方 message/send 回复。
 */
@Service
public class WecomAppService implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(WecomAppService.class);

    private final BridgeProperties properties;
    private final WecomApiClient apiClient;
    private final InboxRecorder recorder;
    private final Map<String, String> nameCache = new ConcurrentHashMap<>();

    public WecomAppService(BridgeProperties properties, WecomApiClient apiClient, InboxRecorder recorder) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.recorder = recorder;
    }

    @Override
    public InboxChannel channel() {
        return InboxChannel.WECOM_APP;
    }

    @Override
    public boolean configured() {
        return properties.isEnabled() && properties.getApp().isConfigured();
    }

    /**
     * 处理应用回调的明文 XML 字段。
     */
    public boolean handleCallback(Map<String, String> fields) {
        String fromUser = fields.getOrDefault("FromUserName", "");
        if (fromUser.isBlank()) {
            return false;
        }

        String msgType = fields.getOrDefault("MsgType", "text");
        long createTime = parseLong(fields.get("CreateTime")) * 1000L;
        String msgId = fields.getOrDefault("MsgId", "");

        String content;
        String mediaId = null;
        MessageDirection direction = MessageDirection.INBOUND;

        switch (msgType) {
            case "text" -> content = fields.getOrDefault("Content", "");
            case "image" -> {
                content = "[图片]";
                mediaId = fields.get("MediaId");
            }
            case "voice" -> {
                content = "[语音]";
                mediaId = fields.get("MediaId");
            }
            case "video" -> {
                content = "[视频]";
                mediaId = fields.get("MediaId");
            }
            case "file" -> {
                content = "[文件]";
                mediaId = fields.get("MediaId");
            }
            case "location" -> content = "[位置] " + fields.getOrDefault("Label", "");
            case "link" -> content = "[链接] " + fields.getOrDefault("Title", "");
            case "event" -> {
                content = "成员事件：" + fields.getOrDefault("Event", "");
                direction = MessageDirection.SYSTEM;
                if (msgId.isBlank()) {
                    msgId = "event-" + fromUser + "-" + fields.getOrDefault("CreateTime", "");
                }
            }
            default -> content = "[" + msgType + "]";
        }

        return recorder.recordInbound(InboxRecorder.InboundRecord
                .builder(InboxChannel.WECOM_APP, fromUser)
                .messageId(msgId)
                .peerName(resolveName(fromUser))
                .direction(direction)
                .msgtype("event".equals(msgType) ? "event" : msgType)
                .content(content)
                .mediaId(mediaId)
                .createTime(createTime)
                .build()).isPresent();
    }

    @Override
    public SendOutcome send(InboxConversation conversation, String text) {
        if (!configured()) {
            return SendOutcome.failed("企微成员通道未配置：需要 corp-id、agent-id、应用 secret 与回调参数");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", conversation.getPeerId());
        body.put("msgtype", "text");
        body.put("agentid", properties.getApp().getAgentId());
        body.put("text", Map.of("content", text));
        body.put("safe", 0);

        try {
            JsonNode response = apiClient.post("/cgi-bin/message/send", properties.getApp().getSecret(), body);
            String invalidUser = response.path("invaliduser").asText("");
            if (!invalidUser.isBlank()) {
                return SendOutcome.failed("成员不在应用可见范围或不存在：" + invalidUser);
            }
            return SendOutcome.ok(response.path("msgid").asText(""));
        } catch (WecomApiException e) {
            return SendOutcome.failed(e.getErrmsg() == null || e.getErrmsg().isBlank()
                    ? "官方接口返回错误码 " + e.getErrcode()
                    : "官方接口错误 " + e.getErrcode() + "：" + e.getErrmsg());
        }
    }

    /**
     * 读取成员姓名。未授予通讯录权限时静默降级为 UserID。
     */
    private String resolveName(String userId) {
        String cached = nameCache.get(userId);
        if (cached != null) {
            return cached;
        }
        if (!configured()) {
            return null;
        }
        try {
            JsonNode response = apiClient.get("/cgi-bin/user/get", properties.getApp().getSecret(), Map.of("userid", userId));
            String name = response.path("name").asText("");
            if (!name.isBlank()) {
                nameCache.put(userId, name);
                return name;
            }
        } catch (WecomApiException e) {
            log.debug("读取成员姓名失败（可能未开通通讯录权限）: {}", e.getMessage());
        }
        return null;
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

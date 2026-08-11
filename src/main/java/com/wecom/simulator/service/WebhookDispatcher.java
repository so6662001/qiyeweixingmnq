package com.wecom.simulator.service;

import com.wecom.simulator.model.ChatType;
import com.wecom.simulator.model.Message;
import com.wecom.simulator.model.MessageType;
import com.wecom.simulator.model.ProductChannel;
import com.wecom.simulator.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final MessageStore store;
    private final ProductChannel channel;
    private final RestClient restClient;

    public WebhookDispatcher(MessageStore store, ProductChannel channel) {
        this.store = store;
        this.channel = channel;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public Map<String, Object> toCallbackPayload(Message message) {
        if (channel.isWechat()) {
            return toWechatCallbackPayload(message);
        }
        return toWecomCallbackPayload(message);
    }

    /** 兼容旧方法名。 */
    public Map<String, Object> toWecomCallbackPayload(Message message) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("ToUserName", channel.getWebhookToUser());
        base.put("FromUserName", message.getFromUser());
        base.put("CreateTime", message.getCreateTime());
        base.put("MsgId", message.getMsgid());
        base.put("AgentID", message.getAgentId());
        base.put("MsgType", message.getMsgtype().getValue());
        base.put("ChatType", message.getChatType() == null ? ChatType.PRIVATE.getValue() : message.getChatType().getValue());
        if (message.getChatType() == ChatType.GROUP) {
            base.put("GroupId", message.getGroupId());
            base.put("GroupName", message.getGroupName());
        }
        putSharedFields(base, message);
        putMediaFields(base, message);
        return base;
    }

    /**
     * 个人微信风格回调载荷（本地联调假实现，非官方协议）。
     */
    public Map<String, Object> toWechatCallbackPayload(Message message) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("ToUserName", channel.getWebhookToUser());
        base.put("FromUserName", message.getFromUser());
        base.put("CreateTime", message.getCreateTime());
        base.put("MsgId", message.getMsgid());
        base.put("MsgType", message.getMsgtype().getValue());
        base.put("Product", channel.getId());
        base.put("ChatType", message.getChatType() == null ? ChatType.PRIVATE.getValue() : message.getChatType().getValue());
        if (message.getChatType() == ChatType.GROUP) {
            base.put("GroupId", message.getGroupId());
            base.put("GroupName", message.getGroupName());
        }
        putSharedFields(base, message);
        putMediaFields(base, message);
        return base;
    }

    private static void putSharedFields(Map<String, Object> base, Message message) {
        if (message.getReplyToMsgid() != null) {
            base.put("ReplyToMsgId", message.getReplyToMsgid());
        }
        if (message.getReplyToUser() != null) {
            base.put("ReplyToUser", message.getReplyToUser());
            base.put("ReplyToUserName", message.getReplyToUserName());
            base.put("ReplyToContent", message.getReplyToContent());
        }
        if (message.getMentionUserIds() != null && !message.getMentionUserIds().isEmpty()) {
            base.put("MentionUserIds", message.getMentionUserIds());
        }
    }

    private static void putMediaFields(Map<String, Object> base, Message message) {
        if (message.getMsgtype() == MessageType.TEXT) {
            base.put("Content", message.getContent() == null ? "" : message.getContent());
        } else if (message.getMsgtype() == MessageType.VOICE) {
            base.put("MediaId", message.getMediaId() == null ? "" : message.getMediaId());
            Object format = message.getRaw().getOrDefault("Format", "webm");
            base.put("Format", format);
            base.put("Recognition", message.getRecognition() == null ? "" : message.getRecognition());
            base.put("VoiceUrl", message.getVoiceUrl() == null ? "" : message.getVoiceUrl());
            if (message.getVoiceDurationMs() != null) {
                base.put("VoiceDurationMs", message.getVoiceDurationMs());
            }
        } else if (message.getMsgtype() == MessageType.IMAGE) {
            base.put("MediaId", message.getMediaId() == null ? "" : message.getMediaId());
            base.put("PicUrl", message.getImageUrl() == null ? "" : message.getImageUrl());
            base.put("FileName", message.getFileName() == null ? "" : message.getFileName());
        } else if (message.getMsgtype() == MessageType.FILE) {
            base.put("MediaId", message.getMediaId() == null ? "" : message.getMediaId());
            base.put("FileUrl", message.getFileUrl() == null ? "" : message.getFileUrl());
            base.put("FileName", message.getFileName() == null ? "" : message.getFileName());
            base.put("FileSize", message.getFileSize() == null ? 0 : message.getFileSize());
        }
    }

    public Map<String, Object> dispatchInbound(Message message) {
        if (!store.isWebhookEnabled() || store.getWebhookUrl() == null || store.getWebhookUrl().isBlank()) {
            return null;
        }
        Map<String, Object> payload = toCallbackPayload(message);
        try {
            var response = restClient.post()
                    .uri(store.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toEntity(String.class);
            String body = response.getBody() == null ? "" : response.getBody();
            if (body.length() > 2000) {
                body = body.substring(0, 2000);
            }
            int code = response.getStatusCode().value();
            log.info("webhook dispatched product={} msgid={} status={}", channel.getId(), message.getMsgid(), code);
            return Map.of(
                    "status_code", code,
                    "body", body,
                    "ok", code >= 200 && code < 300
            );
        } catch (Exception ex) {
            log.warn("webhook failed product={} msgid={}: {}", channel.getId(), message.getMsgid(), ex.getMessage());
            return Map.of("ok", false, "error", ex.getMessage() == null ? "error" : ex.getMessage());
        }
    }
}

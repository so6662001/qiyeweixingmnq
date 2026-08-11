package com.wecom.simulator.service;

import com.wecom.simulator.model.Message;
import com.wecom.simulator.model.MessageType;
import com.wecom.simulator.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final MessageStore store;
    private final RestClient restClient;

    public WebhookDispatcher(MessageStore store) {
        this.store = store;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public Map<String, Object> toWecomCallbackPayload(Message message) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("ToUserName", "ww_simulator");
        base.put("FromUserName", message.getFromUser());
        base.put("CreateTime", message.getCreateTime());
        base.put("MsgId", message.getMsgid());
        base.put("AgentID", message.getAgentId());
        base.put("MsgType", message.getMsgtype().getValue());

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
        }
        return base;
    }

    public Map<String, Object> dispatchInbound(Message message) {
        if (!store.isWebhookEnabled() || store.getWebhookUrl() == null || store.getWebhookUrl().isBlank()) {
            return null;
        }
        Map<String, Object> payload = toWecomCallbackPayload(message);
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
            log.info("webhook dispatched msgid={} status={}", message.getMsgid(), code);
            return Map.of(
                    "status_code", code,
                    "body", body,
                    "ok", code >= 200 && code < 300
            );
        } catch (Exception ex) {
            log.warn("webhook failed msgid={}: {}", message.getMsgid(), ex.getMessage());
            return Map.of("ok", false, "error", ex.getMessage() == null ? "error" : ex.getMessage());
        }
    }
}

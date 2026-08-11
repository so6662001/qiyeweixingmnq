package com.wecom.simulator.store;

import com.wecom.simulator.dto.SessionState;
import com.wecom.simulator.model.Message;
import com.wecom.simulator.model.MessageType;
import com.wecom.simulator.model.SenderRole;
import com.wecom.simulator.web.RealtimeHub;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class MessageStore {

    private final RealtimeHub realtimeHub;
    private final List<Message> messages = new CopyOnWriteArrayList<>();
    private volatile String sessionId = shortId();
    private volatile String webhookUrl;
    private volatile boolean webhookEnabled;
    private volatile boolean demoBotEnabled;

    public MessageStore(
            RealtimeHub realtimeHub,
            @Value("${wecom.simulator.demo-bot-enabled:true}") boolean demoBotEnabled
    ) {
        this.realtimeHub = realtimeHub;
        this.demoBotEnabled = demoBotEnabled;
    }

    public SessionState snapshot() {
        SessionState state = new SessionState();
        state.setSessionId(sessionId);
        state.setMessages(new ArrayList<>(messages));
        state.setWebhookUrl(webhookUrl);
        state.setWebhookEnabled(webhookEnabled);
        state.setDemoBotEnabled(demoBotEnabled);
        return state;
    }

    public Message addTextFromUser(String content, String fromUser, String agentId) {
        Message msg = baseUserMessage(MessageType.TEXT, fromUser, agentId);
        msg.setContent(content);
        Map<String, Object> raw = msg.getRaw();
        raw.put("MsgType", "text");
        raw.put("Content", content);
        raw.put("FromUserName", fromUser);
        raw.put("AgentID", agentId);
        messages.add(msg);
        broadcastMessage(msg);
        return msg;
    }

    public Message addVoiceFromUser(
            String mediaId,
            String voiceUrl,
            String fromUser,
            String agentId,
            Integer durationMs,
            String recognition,
            String format
    ) {
        Message msg = baseUserMessage(MessageType.VOICE, fromUser, agentId);
        msg.setMediaId(mediaId);
        msg.setVoiceUrl(voiceUrl);
        msg.setVoiceDurationMs(durationMs);
        msg.setRecognition(recognition);
        msg.setContent(recognition);
        Map<String, Object> raw = msg.getRaw();
        raw.put("MsgType", "voice");
        raw.put("MediaId", mediaId);
        raw.put("Format", format == null || format.isBlank() ? "webm" : format);
        raw.put("Recognition", recognition == null ? "" : recognition);
        raw.put("FromUserName", fromUser);
        raw.put("AgentID", agentId);
        messages.add(msg);
        broadcastMessage(msg);
        return msg;
    }

    public Message addBotReply(String content, String toUser, String agentId, String replyToMsgid) {
        Message msg = new Message();
        msg.setMsgid(UUID.randomUUID().toString().replace("-", ""));
        msg.setMsgtype(MessageType.TEXT);
        msg.setRole(SenderRole.BOT);
        msg.setFromUser("bot");
        msg.setToUser(toUser);
        msg.setAgentId(agentId);
        msg.setContent(content);
        msg.setCreateTime(System.currentTimeMillis() / 1000);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("msgtype", "text");
        raw.put("touser", toUser);
        raw.put("agentid", agentId);
        raw.put("text", Map.of("content", content));
        raw.put("reply_to_msgid", replyToMsgid);
        msg.setRaw(raw);
        messages.add(msg);
        broadcastMessage(msg);
        return msg;
    }

    public void clear() {
        messages.clear();
        sessionId = shortId();
        realtimeHub.broadcast(Map.of(
                "type", "cleared",
                "session_id", sessionId
        ));
    }

    public Optional<Message> findById(String msgid) {
        return messages.stream().filter(m -> m.getMsgid().equals(msgid)).findFirst();
    }

    public List<Message> list(MessageType msgtype, SenderRole role, String afterMsgid) {
        List<Message> items = new ArrayList<>(messages);
        if (afterMsgid != null && !afterMsgid.isBlank()) {
            int idx = -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getMsgid().equals(afterMsgid)) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                items = items.subList(idx + 1, items.size());
            }
        }
        return items.stream()
                .filter(m -> msgtype == null || m.getMsgtype() == msgtype)
                .filter(m -> role == null || m.getRole() == role)
                .toList();
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public boolean isWebhookEnabled() {
        return webhookEnabled;
    }

    public void setWebhookEnabled(boolean webhookEnabled) {
        this.webhookEnabled = webhookEnabled;
    }

    public boolean isDemoBotEnabled() {
        return demoBotEnabled;
    }

    public void setDemoBotEnabled(boolean demoBotEnabled) {
        this.demoBotEnabled = demoBotEnabled;
    }

    public void broadcastEvent(Map<String, Object> event) {
        realtimeHub.broadcast(event);
    }

    private Message baseUserMessage(MessageType type, String fromUser, String agentId) {
        Message msg = new Message();
        msg.setMsgid(UUID.randomUUID().toString().replace("-", ""));
        msg.setMsgtype(type);
        msg.setRole(SenderRole.USER);
        msg.setFromUser(fromUser);
        msg.setAgentId(agentId);
        msg.setCreateTime(System.currentTimeMillis() / 1000);
        msg.setRaw(new LinkedHashMap<>());
        return msg;
    }

    private void broadcastMessage(Message msg) {
        realtimeHub.broadcast(Map.of(
                "type", "message",
                "message", msg
        ));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

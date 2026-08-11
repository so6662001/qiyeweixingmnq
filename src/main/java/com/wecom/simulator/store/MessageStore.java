package com.wecom.simulator.store;

import com.wecom.simulator.dto.SessionState;
import com.wecom.simulator.model.ChatGroup;
import com.wecom.simulator.model.ChatType;
import com.wecom.simulator.model.Message;
import com.wecom.simulator.model.MessageType;
import com.wecom.simulator.model.ProductChannel;
import com.wecom.simulator.model.SenderRole;
import com.wecom.simulator.web.RealtimeHub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class MessageStore {

    private final RealtimeHub realtimeHub;
    private final ProductChannel channel;
    private final List<Message> messages = new CopyOnWriteArrayList<>();
    private final Map<String, ChatGroup> groups = new ConcurrentHashMap<>();
    private volatile String sessionId = shortId();
    private volatile String webhookUrl;
    private volatile boolean webhookEnabled;
    private volatile boolean demoBotEnabled;

    public MessageStore(
            RealtimeHub realtimeHub,
            ProductChannel channel,
            boolean demoBotEnabled
    ) {
        this.realtimeHub = realtimeHub;
        this.channel = channel;
        this.demoBotEnabled = demoBotEnabled;
        seedDefaultGroup();
    }

    private void seedDefaultGroup() {
        String groupId = channel.getDefaultGroupId();
        if (!groups.containsKey(groupId)) {
            ChatGroup group = new ChatGroup();
            group.setGroupId(groupId);
            group.setName(channel.getDefaultGroupName());
            group.setOwnerId(channel.isWechat() ? "me001" : "seller001");
            if (channel.isWechat()) {
                group.setMemberIds(new ArrayList<>(List.of("friend001", "friend002", "me001", "bot")));
            } else {
                group.setMemberIds(new ArrayList<>(List.of("user001", "user002", "seller001", "bot")));
            }
            group.setCreateTime(System.currentTimeMillis() / 1000);
            groups.put(group.getGroupId(), group);
        }
    }

    public ProductChannel getChannel() {
        return channel;
    }

    public SessionState snapshot() {
        SessionState state = new SessionState();
        state.setSessionId(sessionId);
        state.setProduct(channel.getId());
        state.setMessages(new ArrayList<>(messages));
        state.setGroups(listGroups());
        state.setWebhookUrl(webhookUrl);
        state.setWebhookEnabled(webhookEnabled);
        state.setDemoBotEnabled(demoBotEnabled);
        return state;
    }

    public List<ChatGroup> listGroups() {
        return new ArrayList<>(groups.values());
    }

    public Optional<ChatGroup> findGroup(String groupId) {
        return Optional.ofNullable(groups.get(groupId));
    }

    public ChatGroup createGroup(ChatGroup group) {
        groups.put(group.getGroupId(), group);
        realtimeHub.broadcast(channel, Map.of("type", "group", "group", group));
        return group;
    }

    public Message addInbound(Message msg) {
        messages.add(msg);
        broadcastMessage(msg);
        return msg;
    }

    public Message addBotReply(Message msg) {
        messages.add(msg);
        broadcastMessage(msg);
        return msg;
    }

    public void clear() {
        messages.clear();
        sessionId = shortId();
        realtimeHub.broadcast(channel, Map.of(
                "type", "cleared",
                "session_id", sessionId
        ));
    }

    public Optional<Message> findById(String msgid) {
        return messages.stream().filter(m -> m.getMsgid().equals(msgid)).findFirst();
    }

    public List<Message> list(
            MessageType msgtype,
            SenderRole role,
            String afterMsgid,
            ChatType chatType,
            String groupId
    ) {
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
                .filter(m -> chatType == null || m.getChatType() == chatType)
                .filter(m -> groupId == null || groupId.isBlank() || groupId.equals(m.getGroupId()))
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
        realtimeHub.broadcast(channel, event);
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static Map<String, Object> baseRaw(Message msg) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("MsgType", msg.getMsgtype().getValue());
        raw.put("FromUserName", msg.getFromUser());
        raw.put("AgentID", msg.getAgentId());
        raw.put("ChatType", msg.getChatType().getValue());
        if (msg.getGroupId() != null) {
            raw.put("GroupId", msg.getGroupId());
            raw.put("GroupName", msg.getGroupName());
        }
        if (msg.getReplyToMsgid() != null) {
            raw.put("ReplyToMsgId", msg.getReplyToMsgid());
        }
        if (msg.getReplyToUser() != null) {
            raw.put("ReplyToUser", msg.getReplyToUser());
        }
        if (msg.getMentionUserIds() != null && !msg.getMentionUserIds().isEmpty()) {
            raw.put("MentionUserIds", msg.getMentionUserIds());
        }
        return raw;
    }

    private void broadcastMessage(Message msg) {
        realtimeHub.broadcast(channel, Map.of(
                "type", "message",
                "message", msg
        ));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

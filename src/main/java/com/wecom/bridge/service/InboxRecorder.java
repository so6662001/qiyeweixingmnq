package com.wecom.bridge.service;

import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.model.MessageDirection;
import com.wecom.bridge.model.SendState;
import com.wecom.bridge.store.InboxStore;
import com.wecom.bridge.web.InboxRealtimeHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息入库与实时推送的唯一入口，供 OpenClaw 入站与会话存档入站复用。
 */
@Component
public class InboxRecorder {

    private static final Logger log = LoggerFactory.getLogger(InboxRecorder.class);

    private final InboxStore store;
    private final InboxRealtimeHub hub;

    public InboxRecorder(InboxStore store, InboxRealtimeHub hub) {
        this.store = store;
        this.hub = hub;
    }

    /**
     * 记录一条来源侧消息。重复 msgid 会被丢弃（重投、游标重放都可能重复）。
     */
    public Optional<InboxMessage> record(InboundRecord record) {
        if (!store.markSeen(record.messageId())) {
            log.debug("忽略重复消息 {}", record.messageId());
            return Optional.empty();
        }

        InboxConversation conversation = store.upsertConversation(record.channel(), record.peerId());
        applyConversationFields(conversation, record);

        InboxMessage message = new InboxMessage();
        message.setId(record.messageId() == null || record.messageId().isBlank()
                ? "local-" + UUID.randomUUID() : record.messageId());
        message.setChannel(record.channel());
        message.setConversationId(conversation.getId());
        message.setDirection(record.direction());
        message.setMsgtype(record.msgtype());
        message.setContent(record.content());
        message.setMediaId(record.mediaId());
        message.setSenderId(record.direction() == MessageDirection.OUTBOUND
                ? record.ownerUserid() : record.peerId());
        message.setSenderName(record.direction() == MessageDirection.OUTBOUND
                ? "我" : conversation.displayName());
        message.setCreateTime(record.createTime() > 0 ? record.createTime() : System.currentTimeMillis());
        message.setArchiveSeq(record.archiveSeq());
        message.setSendState(record.direction() == MessageDirection.OUTBOUND ? SendState.SENT : SendState.NONE);

        store.append(message);
        publish(conversation, message);
        return Optional.of(message);
    }

    private void applyConversationFields(InboxConversation conversation, InboundRecord record) {
        if (record.peerName() != null && !record.peerName().isBlank()) {
            conversation.setPeerName(record.peerName());
        }
        if (record.openclawTarget() != null && !record.openclawTarget().isBlank()) {
            conversation.setOpenclawTarget(record.openclawTarget());
        }
        if (record.ownerUserid() != null && !record.ownerUserid().isBlank()) {
            conversation.setOwnerUserid(record.ownerUserid());
        }
        if (record.roomId() != null && !record.roomId().isBlank()) {
            conversation.setRoomId(record.roomId());
        }
    }

    /**
     * 记录本系统内录入、即将通过 OpenClaw 发出的消息。
     */
    public InboxMessage recordOutbound(InboxConversation conversation, String text) {
        InboxMessage message = new InboxMessage();
        message.setId("out-" + UUID.randomUUID());
        message.setChannel(conversation.getChannel());
        message.setConversationId(conversation.getId());
        message.setDirection(MessageDirection.OUTBOUND);
        message.setMsgtype("text");
        message.setContent(text);
        message.setSenderName("我");
        message.setOpenclawTarget(conversation.getOpenclawTarget());
        message.setCreateTime(System.currentTimeMillis());
        message.setSendState(SendState.NONE);
        store.append(message);
        return message;
    }

    public void markSent(InboxConversation conversation, InboxMessage message, String outboundMessageId) {
        message.setSendState(SendState.SENT);
        message.setErrorMessage(null);
        if (outboundMessageId != null && !outboundMessageId.isBlank()) {
            store.markSeen(outboundMessageId);
        }
        store.markDirty();
        publish(conversation, message);
    }

    public void markFailed(InboxConversation conversation, InboxMessage message, String reason) {
        message.setSendState(SendState.FAILED);
        message.setErrorMessage(reason);
        store.markDirty();
        publish(conversation, message);
    }

    public void markLocal(InboxConversation conversation, InboxMessage message) {
        message.setSendState(SendState.LOCAL);
        store.markDirty();
        publish(conversation, message);
    }

    public void publishConversation(InboxConversation conversation) {
        store.markDirty();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversation", conversation);
        hub.broadcast("conversation", payload);
    }

    private void publish(InboxConversation conversation, InboxMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("conversation", conversation);
        payload.put("total_unread", store.totalUnread());
        hub.broadcast("message", payload);
    }

    /**
     * 入站记录参数。
     */
    public record InboundRecord(
            InboxChannel channel,
            String messageId,
            String peerId,
            String peerName,
            String openclawTarget,
            String ownerUserid,
            String roomId,
            MessageDirection direction,
            String msgtype,
            String content,
            String mediaId,
            long createTime,
            Long archiveSeq
    ) {
        public static Builder builder(InboxChannel channel, String peerId) {
            return new Builder(channel, peerId);
        }

        public static final class Builder {
            private final InboxChannel channel;
            private final String peerId;
            private String messageId;
            private String peerName;
            private String openclawTarget;
            private String ownerUserid;
            private String roomId;
            private MessageDirection direction = MessageDirection.INBOUND;
            private String msgtype = "text";
            private String content = "";
            private String mediaId;
            private long createTime;
            private Long archiveSeq;

            private Builder(InboxChannel channel, String peerId) {
                this.channel = channel;
                this.peerId = peerId;
            }

            public Builder messageId(String value) {
                this.messageId = value;
                return this;
            }

            public Builder peerName(String value) {
                this.peerName = value;
                return this;
            }

            public Builder openclawTarget(String value) {
                this.openclawTarget = value;
                return this;
            }

            public Builder ownerUserid(String value) {
                this.ownerUserid = value;
                return this;
            }

            public Builder roomId(String value) {
                this.roomId = value;
                return this;
            }

            public Builder direction(MessageDirection value) {
                this.direction = value;
                return this;
            }

            public Builder msgtype(String value) {
                this.msgtype = value == null || value.isBlank() ? "text" : value;
                return this;
            }

            public Builder content(String value) {
                this.content = value == null ? "" : value;
                return this;
            }

            public Builder mediaId(String value) {
                this.mediaId = value;
                return this;
            }

            public Builder createTime(long value) {
                this.createTime = value;
                return this;
            }

            public Builder archiveSeq(Long value) {
                this.archiveSeq = value;
                return this;
            }

            public InboundRecord build() {
                return new InboundRecord(channel, messageId, peerId, peerName, openclawTarget, ownerUserid,
                        roomId, direction, msgtype, content, mediaId, createTime, archiveSeq);
            }
        }
    }
}

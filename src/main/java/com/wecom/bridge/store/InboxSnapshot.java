package com.wecom.bridge.store;

import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 落盘快照结构，重启后可恢复会话与拉取游标。
 */
public class InboxSnapshot {

    private int version = 1;

    private List<InboxConversation> conversations = new ArrayList<>();

    private List<InboxMessage> messages = new ArrayList<>();

    /** open_kfid -> next_cursor，微信客服增量拉取必须持久化，否则会重复或漏消息。 */
    private Map<String, String> cursors = new LinkedHashMap<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<InboxConversation> getConversations() {
        return conversations;
    }

    public void setConversations(List<InboxConversation> conversations) {
        this.conversations = conversations == null ? new ArrayList<>() : conversations;
    }

    public List<InboxMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<InboxMessage> messages) {
        this.messages = messages == null ? new ArrayList<>() : messages;
    }

    public Map<String, String> getCursors() {
        return cursors;
    }

    public void setCursors(Map<String, String> cursors) {
        this.cursors = cursors == null ? new LinkedHashMap<>() : cursors;
    }
}

package com.wecom.simulator.dto;

import java.util.ArrayList;
import java.util.List;

public class InboundChatOptions {

    private String chatType = "private";
    private String groupId;
    private String fromUser = "user001";
    private String fromUserName;
    private String agentId = "1000001";
    private String replyToMsgid;
    private String replyToUser;
    private List<String> mentionUserIds = new ArrayList<>();

    public String getChatType() {
        return chatType;
    }

    public void setChatType(String chatType) {
        this.chatType = chatType;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getFromUser() {
        return fromUser;
    }

    public void setFromUser(String fromUser) {
        this.fromUser = fromUser;
    }

    public String getFromUserName() {
        return fromUserName;
    }

    public void setFromUserName(String fromUserName) {
        this.fromUserName = fromUserName;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getReplyToMsgid() {
        return replyToMsgid;
    }

    public void setReplyToMsgid(String replyToMsgid) {
        this.replyToMsgid = replyToMsgid;
    }

    public String getReplyToUser() {
        return replyToUser;
    }

    public void setReplyToUser(String replyToUser) {
        this.replyToUser = replyToUser;
    }

    public List<String> getMentionUserIds() {
        return mentionUserIds;
    }

    public void setMentionUserIds(List<String> mentionUserIds) {
        this.mentionUserIds = mentionUserIds;
    }
}

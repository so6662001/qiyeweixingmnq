package com.wecom.simulator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

public class ReplyTextRequest {

    @NotBlank
    @Size(max = 4000)
    private String content;

    @Size(max = 16)
    private String chatType = "private";

    @Size(max = 64)
    private String groupId;

    @Size(max = 64)
    private String toUser;

    /** 群内回复指定的人 */
    @Size(max = 64)
    private String replyToUser;

    @Size(max = 32)
    private String agentId = "1000001";

    private String replyToMsgid;

    private List<String> mentionUserIds = new ArrayList<>();

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

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

    public String getToUser() {
        return toUser;
    }

    public void setToUser(String toUser) {
        this.toUser = toUser;
    }

    public String getReplyToUser() {
        return replyToUser;
    }

    public void setReplyToUser(String replyToUser) {
        this.replyToUser = replyToUser;
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

    public List<String> getMentionUserIds() {
        return mentionUserIds;
    }

    public void setMentionUserIds(List<String> mentionUserIds) {
        this.mentionUserIds = mentionUserIds;
    }
}

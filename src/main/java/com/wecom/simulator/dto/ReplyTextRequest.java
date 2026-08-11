package com.wecom.simulator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ReplyTextRequest {

    @NotBlank
    @Size(max = 4000)
    private String content;

    @Size(max = 64)
    private String toUser;

    @Size(max = 32)
    private String agentId = "1000001";

    private String replyToMsgid;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToUser() {
        return toUser;
    }

    public void setToUser(String toUser) {
        this.toUser = toUser;
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
}

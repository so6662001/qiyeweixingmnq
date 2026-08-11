package com.wecom.simulator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TextMessageRequest {

    @NotBlank
    @Size(max = 4000)
    private String content;

    @Size(max = 64)
    private String fromUser = "user001";

    @Size(max = 32)
    private String agentId = "1000001";

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getFromUser() {
        return fromUser;
    }

    public void setFromUser(String fromUser) {
        this.fromUser = fromUser;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }
}

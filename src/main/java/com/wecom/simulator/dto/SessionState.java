package com.wecom.simulator.dto;

import com.wecom.simulator.model.ChatGroup;
import com.wecom.simulator.model.Message;

import java.util.ArrayList;
import java.util.List;

public class SessionState {

    private String sessionId;
    private String product;
    private List<Message> messages = new ArrayList<>();
    private List<ChatGroup> groups = new ArrayList<>();
    private String webhookUrl;
    private boolean webhookEnabled;
    private boolean demoBotEnabled;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public List<ChatGroup> getGroups() {
        return groups;
    }

    public void setGroups(List<ChatGroup> groups) {
        this.groups = groups;
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
}

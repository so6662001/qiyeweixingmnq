package com.wecom.simulator.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class Message {

    private String msgid;
    private MessageType msgtype;
    private SenderRole role;
    private String fromUser;
    private String toUser;
    private String agentId;
    private String content;
    private String mediaId;
    private String voiceUrl;
    private Integer voiceDurationMs;
    private String recognition;
    private long createTime;
    private Map<String, Object> raw = new LinkedHashMap<>();

    public String getMsgid() {
        return msgid;
    }

    public void setMsgid(String msgid) {
        this.msgid = msgid;
    }

    public MessageType getMsgtype() {
        return msgtype;
    }

    public void setMsgtype(MessageType msgtype) {
        this.msgtype = msgtype;
    }

    public SenderRole getRole() {
        return role;
    }

    public void setRole(SenderRole role) {
        this.role = role;
    }

    public String getFromUser() {
        return fromUser;
    }

    public void setFromUser(String fromUser) {
        this.fromUser = fromUser;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMediaId() {
        return mediaId;
    }

    public void setMediaId(String mediaId) {
        this.mediaId = mediaId;
    }

    public String getVoiceUrl() {
        return voiceUrl;
    }

    public void setVoiceUrl(String voiceUrl) {
        this.voiceUrl = voiceUrl;
    }

    public Integer getVoiceDurationMs() {
        return voiceDurationMs;
    }

    public void setVoiceDurationMs(Integer voiceDurationMs) {
        this.voiceDurationMs = voiceDurationMs;
    }

    public String getRecognition() {
        return recognition;
    }

    public void setRecognition(String recognition) {
        this.recognition = recognition;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public Map<String, Object> getRaw() {
        return raw;
    }

    public void setRaw(Map<String, Object> raw) {
        this.raw = raw;
    }
}

package com.wecom.simulator.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Message {

    private String msgid;
    private MessageType msgtype;
    private SenderRole role;
    private ChatType chatType = ChatType.PRIVATE;
    private String fromUser;
    private String fromUserName;
    private String toUser;
    private String groupId;
    private String groupName;
    private String agentId;
    private String content;
    private String mediaId;
    private String voiceUrl;
    private String imageUrl;
    private String fileUrl;
    private String fileName;
    private Long fileSize;
    private Integer voiceDurationMs;
    private String recognition;
    /** 回复的原消息 ID */
    private String replyToMsgid;
    /** 群内回复指定的人 */
    private String replyToUser;
    private String replyToUserName;
    private String replyToContent;
    /** @ 提及的用户 ID 列表 */
    private List<String> mentionUserIds = new ArrayList<>();
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

    public ChatType getChatType() {
        return chatType;
    }

    public void setChatType(ChatType chatType) {
        this.chatType = chatType;
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

    public String getToUser() {
        return toUser;
    }

    public void setToUser(String toUser) {
        this.toUser = toUser;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
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

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
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

    public String getReplyToUserName() {
        return replyToUserName;
    }

    public void setReplyToUserName(String replyToUserName) {
        this.replyToUserName = replyToUserName;
    }

    public String getReplyToContent() {
        return replyToContent;
    }

    public void setReplyToContent(String replyToContent) {
        this.replyToContent = replyToContent;
    }

    public List<String> getMentionUserIds() {
        return mentionUserIds;
    }

    public void setMentionUserIds(List<String> mentionUserIds) {
        this.mentionUserIds = mentionUserIds;
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

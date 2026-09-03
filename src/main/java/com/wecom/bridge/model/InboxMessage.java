package com.wecom.bridge.model;

/**
 * 统一消息模型：OpenClaw 入站与会话存档入站都归一到这份结构，前端只渲染一种。
 */
public class InboxMessage {

    /** 本地消息 ID（来源侧 msgid 优先，缺失时本地生成）。 */
    private String id;

    private InboxChannel channel;

    private String conversationId;

    private MessageDirection direction = MessageDirection.INBOUND;

    /** text / image / voice / video / file / link / revoke / event 等。 */
    private String msgtype = "text";

    private String content = "";

    /**
     * 媒体引用。个人微信为 OpenClaw 的媒体标识；企业微信存档为 sdkfileid。
     * 本服务不落地媒体内容，只记录引用。
     */
    private String mediaId;

    private String senderId;

    private String senderName;

    /** 毫秒时间戳。 */
    private long createTime;

    private SendState sendState = SendState.NONE;

    /** 发送失败原因，仅出站消息使用。 */
    private String errorMessage;

    /** 出站消息实际使用的 OpenClaw 目标，便于排查发错人。 */
    private String openclawTarget;

    /** 会话存档序号，便于与官方数据对齐排查。 */
    private Long archiveSeq;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public InboxChannel getChannel() {
        return channel;
    }

    public void setChannel(InboxChannel channel) {
        this.channel = channel;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public MessageDirection getDirection() {
        return direction;
    }

    public void setDirection(MessageDirection direction) {
        this.direction = direction;
    }

    public String getMsgtype() {
        return msgtype;
    }

    public void setMsgtype(String msgtype) {
        this.msgtype = msgtype;
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

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public SendState getSendState() {
        return sendState;
    }

    public void setSendState(SendState sendState) {
        this.sendState = sendState;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getOpenclawTarget() {
        return openclawTarget;
    }

    public void setOpenclawTarget(String openclawTarget) {
        this.openclawTarget = openclawTarget;
    }

    public Long getArchiveSeq() {
        return archiveSeq;
    }

    public void setArchiveSeq(Long archiveSeq) {
        this.archiveSeq = archiveSeq;
    }

    /**
     * 会话列表用的一行摘要。
     */
    public String preview() {
        if ("text".equals(msgtype)) {
            return content == null ? "" : content;
        }
        return switch (msgtype == null ? "" : msgtype) {
            case "image" -> "[图片]";
            case "voice" -> "[语音]";
            case "video" -> "[视频]";
            case "file" -> "[文件]";
            case "location" -> "[位置]";
            case "link" -> "[链接]";
            case "card" -> "[名片]";
            case "emotion" -> "[表情]";
            case "weapp" -> "[小程序]";
            case "chatrecord" -> "[聊天记录]";
            case "revoke" -> "[撤回消息]";
            case "event" -> content == null || content.isBlank() ? "[事件]" : content;
            default -> content == null || content.isBlank() ? "[" + msgtype + "]" : content;
        };
    }
}

package com.wecom.bridge.model;

/**
 * 统一消息模型：不同通道的官方消息结构统一成同一份，前端只需渲染一种。
 */
public class InboxMessage {

    /** 本地消息 ID（官方 msgid 优先，缺失时本地生成）。 */
    private String id;

    private InboxChannel channel;

    private String conversationId;

    private MessageDirection direction = MessageDirection.INBOUND;

    /** text / image / voice / file / event 等，沿用官方 msgtype 取值。 */
    private String msgtype = "text";

    private String content = "";

    /** 官方媒体 ID；本服务不落地媒体内容，仅记录引用。 */
    private String mediaId;

    private String senderId;

    private String senderName;

    /** 微信客服专用：客服账号 ID。 */
    private String openKfid;

    /** 出站消息的接待人员 UserID。 */
    private String servicerUserid;

    /** 毫秒时间戳。 */
    private long createTime;

    private SendState sendState = SendState.NONE;

    /** 发送失败原因，仅出站消息使用。 */
    private String errorMessage;

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

    public String getOpenKfid() {
        return openKfid;
    }

    public void setOpenKfid(String openKfid) {
        this.openKfid = openKfid;
    }

    public String getServicerUserid() {
        return servicerUserid;
    }

    public void setServicerUserid(String servicerUserid) {
        this.servicerUserid = servicerUserid;
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
            case "miniprogram" -> "[小程序]";
            case "event" -> content == null || content.isBlank() ? "[事件]" : content;
            default -> "[" + msgtype + "]";
        };
    }
}

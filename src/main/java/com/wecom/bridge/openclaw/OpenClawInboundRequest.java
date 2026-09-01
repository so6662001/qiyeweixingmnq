package com.wecom.bridge.openclaw;

/**
 * OpenClaw 插件回推的入站消息。字段名为 snake_case，与插件发送的一致。
 */
public class OpenClawInboundRequest {

    /** OpenClaw 渠道 id，例如 openclaw-weixin。 */
    private String channel;

    private String accountId;

    /** 来源侧消息 id，用于去重。 */
    private String messageId;

    /** 对端标识，同时作为回复时的发送目标。 */
    private String peerId;

    private String peerName;

    private String msgtype;

    private String content;

    private String mediaId;

    /** 毫秒时间戳；插件也可能给秒，服务端会做兼容。 */
    private Long timestamp;

    /** 群聊标记；本系统只接一对一私聊。 */
    private Boolean group;

    /** 出站镜像标记：为 true 表示这条是本方（含从手机端）发出的消息。 */
    private Boolean outbound;

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    public String getPeerName() {
        return peerName;
    }

    public void setPeerName(String peerName) {
        this.peerName = peerName;
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

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Boolean getGroup() {
        return group;
    }

    public void setGroup(Boolean group) {
        this.group = group;
    }

    public Boolean getOutbound() {
        return outbound;
    }

    public void setOutbound(Boolean outbound) {
        this.outbound = outbound;
    }
}

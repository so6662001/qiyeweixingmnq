package com.wecom.bridge.model;

/**
 * 统一会话：一个客户/成员对应一条会话，页面左侧列表即由此渲染。
 */
public class InboxConversation {

    /** 通道 + 对端 ID 组成的稳定标识。 */
    private String id;

    private InboxChannel channel;

    /** 微信客服为 external_userid；企微成员为 UserID。 */
    private String peerId;

    private String peerName;

    /** 微信客服专用：客服账号 ID，回复时必传。 */
    private String openKfid;

    private long lastMessageAt;

    private String lastPreview = "";

    private int unread;

    /**
     * 对方最后一条消息时间，用于判断官方 48 小时可回复窗口。
     */
    private long lastInboundAt;

    /**
     * 微信客服接待状态：0 未处理 / 1 智能助手 / 2 排队中 / 3 人工接待 / 4 已结束。
     */
    private Integer serviceState;

    private String servicerUserid;

    public static String buildId(InboxChannel channel, String peerId) {
        return channel.getValue() + ":" + peerId;
    }

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

    public String getOpenKfid() {
        return openKfid;
    }

    public void setOpenKfid(String openKfid) {
        this.openKfid = openKfid;
    }

    public long getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(long lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public String getLastPreview() {
        return lastPreview;
    }

    public void setLastPreview(String lastPreview) {
        this.lastPreview = lastPreview;
    }

    public int getUnread() {
        return unread;
    }

    public void setUnread(int unread) {
        this.unread = unread;
    }

    public long getLastInboundAt() {
        return lastInboundAt;
    }

    public void setLastInboundAt(long lastInboundAt) {
        this.lastInboundAt = lastInboundAt;
    }

    public Integer getServiceState() {
        return serviceState;
    }

    public void setServiceState(Integer serviceState) {
        this.serviceState = serviceState;
    }

    public String getServicerUserid() {
        return servicerUserid;
    }

    public void setServicerUserid(String servicerUserid) {
        this.servicerUserid = servicerUserid;
    }

    public String displayName() {
        if (peerName != null && !peerName.isBlank()) {
            return peerName;
        }
        return peerId == null ? "" : peerId;
    }
}

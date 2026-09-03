package com.wecom.bridge.model;

/**
 * 统一会话：一个对端（微信好友 / 企微联系人）对应一条会话，页面左侧列表即由此渲染。
 */
public class InboxConversation {

    /** 通道 + 对端 ID 组成的稳定标识。 */
    private String id;

    private InboxChannel channel;

    /**
     * 对端标识。个人微信为 OpenClaw 的 peer id；企业微信为存档里的 userid / external_userid。
     */
    private String peerId;

    private String peerName;

    /**
     * OpenClaw 发送目标。个人微信默认等于 peerId；企业微信需要映射后才能发送。
     */
    private String openclawTarget;

    /** 企业微信侧本方成员 UserID（存档消息中的企业成员），用于显示归属。 */
    private String ownerUserid;

    /** 存档群聊 id，一对一为空。 */
    private String roomId;

    private long lastMessageAt;

    private String lastPreview = "";

    private int unread;

    /** 对方最后一条消息时间。 */
    private long lastInboundAt;

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

    public String getOpenclawTarget() {
        return openclawTarget;
    }

    public void setOpenclawTarget(String openclawTarget) {
        this.openclawTarget = openclawTarget;
    }

    public String getOwnerUserid() {
        return ownerUserid;
    }

    public void setOwnerUserid(String ownerUserid) {
        this.ownerUserid = ownerUserid;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
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

    public String displayName() {
        if (peerName != null && !peerName.isBlank()) {
            return peerName;
        }
        return peerId == null ? "" : peerId;
    }

    /** 是否已具备发送条件（有 OpenClaw 目标）。 */
    public boolean sendable() {
        return openclawTarget != null && !openclawTarget.isBlank();
    }
}

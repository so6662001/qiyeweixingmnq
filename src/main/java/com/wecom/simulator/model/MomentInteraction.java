package com.wecom.simulator.model;

public class MomentInteraction {

    private String interactionId;
    private String momentId;
    private MomentInteractionType type;
    private String userId;
    private String userName;
    private String content;
    private String momentContent;
    private String momentPlan;
    private String imageUrl;
    private long createTime;
    private boolean syncedToCrm;
    private String crmSyncResult;

    public String getInteractionId() {
        return interactionId;
    }

    public void setInteractionId(String interactionId) {
        this.interactionId = interactionId;
    }

    public String getMomentId() {
        return momentId;
    }

    public void setMomentId(String momentId) {
        this.momentId = momentId;
    }

    public MomentInteractionType getType() {
        return type;
    }

    public void setType(MomentInteractionType type) {
        this.type = type;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMomentContent() {
        return momentContent;
    }

    public void setMomentContent(String momentContent) {
        this.momentContent = momentContent;
    }

    public String getMomentPlan() {
        return momentPlan;
    }

    public void setMomentPlan(String momentPlan) {
        this.momentPlan = momentPlan;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public boolean isSyncedToCrm() {
        return syncedToCrm;
    }

    public void setSyncedToCrm(boolean syncedToCrm) {
        this.syncedToCrm = syncedToCrm;
    }

    public String getCrmSyncResult() {
        return crmSyncResult;
    }

    public void setCrmSyncResult(String crmSyncResult) {
        this.crmSyncResult = crmSyncResult;
    }
}

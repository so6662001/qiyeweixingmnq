package com.wecom.simulator.model;

import java.util.ArrayList;
import java.util.List;

public class MomentPost {

    private String momentId;
    private String authorId;
    private String authorName;
    /** 发朋友圈文案 / 运营方案 */
    private String content;
    private String plan;
    private String imageMediaId;
    private String imageUrl;
    private long createTime;
    private List<MomentLike> likes = new ArrayList<>();
    private List<MomentComment> comments = new ArrayList<>();

    public String getMomentId() {
        return momentId;
    }

    public void setMomentId(String momentId) {
        this.momentId = momentId;
    }

    public String getAuthorId() {
        return authorId;
    }

    public void setAuthorId(String authorId) {
        this.authorId = authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    public String getImageMediaId() {
        return imageMediaId;
    }

    public void setImageMediaId(String imageMediaId) {
        this.imageMediaId = imageMediaId;
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

    public List<MomentLike> getLikes() {
        return likes;
    }

    public void setLikes(List<MomentLike> likes) {
        this.likes = likes;
    }

    public List<MomentComment> getComments() {
        return comments;
    }

    public void setComments(List<MomentComment> comments) {
        this.comments = comments;
    }
}

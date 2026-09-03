package com.wecom.bridge.contact;

import java.util.List;

/**
 * 朋友圈相关的数据结构。
 */
public final class MomentModels {

    private MomentModels() {
    }

    /**
     * 发表请求。图片以本地已上传的 media_id 传入；链接需要 title/url。
     */
    public record MomentDraft(
            String text,
            List<String> imageMediaIds,
            String linkTitle,
            String linkUrl,
            String linkMediaId,
            List<String> senderUserIds,
            List<Integer> senderDepartmentIds,
            List<String> customerTagIds
    ) {
        public boolean hasContent() {
            return (text != null && !text.isBlank())
                    || (imageMediaIds != null && !imageMediaIds.isEmpty())
                    || (linkUrl != null && !linkUrl.isBlank());
        }
    }

    /** 创建发表任务的结果。 */
    public record MomentTaskCreated(String jobId, boolean dryRun) {
    }

    /** 任务创建结果查询。 */
    public record MomentTaskStatus(int status, String statusText, String momentId, List<String> invalidSenders) {
    }

    /** 一条朋友圈的概要。 */
    public record MomentSummary(
            String momentId,
            String creator,
            long createTime,
            int createType,
            int visibleType,
            String text,
            int imageCount,
            String linkTitle
    ) {
        /** create_type：0 企业发表，1 个人发表。 */
        public String createTypeText() {
            return createType == 0 ? "企业发表" : "个人发表";
        }
    }

    /** 某条朋友圈汇总后的互动数据。 */
    public record MomentStats(
            String momentId,
            String creator,
            long createTime,
            String text,
            int senderCount,
            int likeCount,
            int commentCount,
            int customerLikeCount,
            int customerCommentCount,
            List<MomentComment> comments,
            long refreshedAt,
            String error
    ) {
    }

    /** 一条评论。 */
    public record MomentComment(
            String senderUserId,
            String externalUserId,
            String userId,
            String content,
            long createTime
    ) {
        public boolean fromCustomer() {
            return externalUserId != null && !externalUserId.isBlank();
        }
    }
}

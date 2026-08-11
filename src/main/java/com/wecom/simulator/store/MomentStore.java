package com.wecom.simulator.store;

import com.wecom.simulator.dto.CrmConfigState;
import com.wecom.simulator.model.MomentComment;
import com.wecom.simulator.model.MomentInteraction;
import com.wecom.simulator.model.MomentInteractionType;
import com.wecom.simulator.model.MomentLike;
import com.wecom.simulator.model.MomentPost;
import com.wecom.simulator.web.RealtimeHub;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class MomentStore {

    private final RealtimeHub realtimeHub;
    private final List<MomentPost> posts = new CopyOnWriteArrayList<>();
    private final List<MomentInteraction> interactions = new CopyOnWriteArrayList<>();

    private volatile String crmUrl;
    private volatile boolean crmEnabled;
    private volatile boolean crmAutoSync = true;
    private volatile String crmAuthHeader;

    public MomentStore(RealtimeHub realtimeHub) {
        this.realtimeHub = realtimeHub;
    }

    public List<MomentPost> listPosts() {
        List<MomentPost> copy = new ArrayList<>(posts);
        copy.sort((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()));
        return copy;
    }

    public Optional<MomentPost> findPost(String momentId) {
        return posts.stream().filter(p -> p.getMomentId().equals(momentId)).findFirst();
    }

    public MomentPost addPost(MomentPost post) {
        posts.add(0, post);
        realtimeHub.broadcast(Map.of("type", "moment", "moment", post));
        return post;
    }

    public MomentInteraction addLike(MomentPost post, String userId, String userName) {
        boolean exists = post.getLikes().stream().anyMatch(l -> l.getUserId().equals(userId));
        if (exists) {
            throw new IllegalArgumentException("该用户已点赞");
        }
        MomentLike like = new MomentLike();
        like.setLikeId(id());
        like.setUserId(userId);
        like.setUserName(userName);
        like.setCreateTime(now());
        post.getLikes().add(like);

        MomentInteraction interaction = baseInteraction(post, MomentInteractionType.LIKE, userId, userName);
        interaction.setContent("点赞");
        interactions.add(interaction);
        realtimeHub.broadcast(Map.of(
                "type", "moment_interaction",
                "interaction", interaction,
                "moment", post
        ));
        return interaction;
    }

    public MomentInteraction addComment(MomentPost post, String userId, String userName, String content) {
        MomentComment comment = new MomentComment();
        comment.setCommentId(id());
        comment.setUserId(userId);
        comment.setUserName(userName);
        comment.setContent(content);
        comment.setCreateTime(now());
        post.getComments().add(comment);

        MomentInteraction interaction = baseInteraction(post, MomentInteractionType.COMMENT, userId, userName);
        interaction.setContent(content);
        interactions.add(interaction);
        realtimeHub.broadcast(Map.of(
                "type", "moment_interaction",
                "interaction", interaction,
                "moment", post
        ));
        return interaction;
    }

    public List<MomentInteraction> listInteractions(boolean onlyUnsynced, String afterId) {
        List<MomentInteraction> items = new ArrayList<>(interactions);
        if (afterId != null && !afterId.isBlank()) {
            int idx = -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getInteractionId().equals(afterId)) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                items = items.subList(idx + 1, items.size());
            }
        }
        if (onlyUnsynced) {
            items = items.stream().filter(i -> !i.isSyncedToCrm()).toList();
        }
        return items;
    }

    public void clear() {
        posts.clear();
        interactions.clear();
        realtimeHub.broadcast(Map.of("type", "moments_cleared"));
    }

    public CrmConfigState crmSnapshot() {
        CrmConfigState state = new CrmConfigState();
        state.setUrl(crmUrl);
        state.setEnabled(crmEnabled);
        state.setAutoSync(crmAutoSync);
        state.setAuthConfigured(crmAuthHeader != null && !crmAuthHeader.isBlank());
        return state;
    }

    public String getCrmUrl() {
        return crmUrl;
    }

    public void setCrmUrl(String crmUrl) {
        this.crmUrl = crmUrl;
    }

    public boolean isCrmEnabled() {
        return crmEnabled;
    }

    public void setCrmEnabled(boolean crmEnabled) {
        this.crmEnabled = crmEnabled;
    }

    public boolean isCrmAutoSync() {
        return crmAutoSync;
    }

    public void setCrmAutoSync(boolean crmAutoSync) {
        this.crmAutoSync = crmAutoSync;
    }

    public String getCrmAuthHeader() {
        return crmAuthHeader;
    }

    public void setCrmAuthHeader(String crmAuthHeader) {
        this.crmAuthHeader = crmAuthHeader;
    }

    private MomentInteraction baseInteraction(
            MomentPost post,
            MomentInteractionType type,
            String userId,
            String userName
    ) {
        MomentInteraction interaction = new MomentInteraction();
        interaction.setInteractionId(id());
        interaction.setMomentId(post.getMomentId());
        interaction.setType(type);
        interaction.setUserId(userId);
        interaction.setUserName(userName);
        interaction.setMomentContent(post.getContent());
        interaction.setMomentPlan(post.getPlan());
        interaction.setImageUrl(post.getImageUrl());
        interaction.setCreateTime(now());
        interaction.setSyncedToCrm(false);
        return interaction;
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }
}

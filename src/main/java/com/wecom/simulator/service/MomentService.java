package com.wecom.simulator.service;

import com.wecom.simulator.model.MomentInteraction;
import com.wecom.simulator.model.MomentPost;
import com.wecom.simulator.model.ProductChannel;
import com.wecom.simulator.security.SafeIds;
import com.wecom.simulator.store.MomentStore;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public class MomentService {

    private final MomentStore momentStore;
    private final CrmLeadSyncService crmLeadSyncService;
    private final ProductChannel channel;
    private final Path momentImageDir;
    private final long maxMomentImages;

    public MomentService(
            MomentStore momentStore,
            CrmLeadSyncService crmLeadSyncService,
            ProductChannel channel,
            long maxMomentImages
    ) throws IOException {
        this.momentStore = momentStore;
        this.crmLeadSyncService = crmLeadSyncService;
        this.channel = channel;
        this.momentImageDir = Path.of(channel.getMomentImageDir()).toAbsolutePath().normalize();
        this.maxMomentImages = maxMomentImages;
        Files.createDirectories(this.momentImageDir);
    }

    public MomentPost publish(
            MultipartFile image,
            String content,
            String plan,
            String authorId,
            String authorName
    ) throws IOException {
        if (!SafeIds.isSafeToken(authorId)) {
            throw new IllegalArgumentException("author_id 非法");
        }
        if (!SafeIds.isSafeDisplayName(authorName)) {
            throw new IllegalArgumentException("author_name 非法");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("朋友圈文案不能为空");
        }
        if (content.length() > 2000) {
            throw new IllegalArgumentException("朋友圈文案过长");
        }
        String planText = plan == null ? "" : plan.trim();
        if (planText.length() > 2000) {
            throw new IllegalArgumentException("方案过长");
        }
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("请提供朋友圈图片");
        }
        if (countImages() >= maxMomentImages) {
            throw new IllegalArgumentException("朋友圈图片数量已达上限，请先清空");
        }

        String original = image.getOriginalFilename() == null ? "moment.jpg" : image.getOriginalFilename();
        String ext = SafeIds.normalizeImageExtension(original);
        String mediaId = UUID.randomUUID().toString().replace("-", "");
        Path dest = momentImageDir.resolve(mediaId + "." + ext).normalize();
        if (!dest.startsWith(momentImageDir)) {
            throw new IllegalArgumentException("非法图片存储路径");
        }
        try (InputStream in = image.getInputStream()) {
            Files.copy(in, dest);
        }

        MomentPost post = new MomentPost();
        post.setMomentId(mediaId);
        post.setAuthorId(authorId);
        post.setAuthorName(authorName);
        post.setContent(content.trim());
        post.setPlan(planText);
        post.setImageMediaId(mediaId);
        post.setImageUrl(channel.getApiBasePath() + "/moments/media/" + mediaId);
        post.setCreateTime(System.currentTimeMillis() / 1000);
        return momentStore.addPost(post);
    }

    public MomentInteraction like(String momentId, String userId, String userName) {
        requireIdentity(userId, userName);
        MomentPost post = momentStore.findPost(momentId)
                .orElseThrow(() -> new IllegalArgumentException("朋友圈不存在"));
        MomentInteraction interaction = momentStore.addLike(post, userId, userName);
        crmLeadSyncService.maybeAutoSync(interaction);
        return interaction;
    }

    public MomentInteraction comment(String momentId, String userId, String userName, String content) {
        requireIdentity(userId, userName);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("评论不能为空");
        }
        if (content.length() > 1000) {
            throw new IllegalArgumentException("评论过长");
        }
        MomentPost post = momentStore.findPost(momentId)
                .orElseThrow(() -> new IllegalArgumentException("朋友圈不存在"));
        MomentInteraction interaction = momentStore.addComment(post, userId, userName, content.trim());
        crmLeadSyncService.maybeAutoSync(interaction);
        return interaction;
    }

    public List<MomentPost> listPosts() {
        return momentStore.listPosts();
    }

    public List<MomentInteraction> listInteractions(boolean onlyUnsynced, String after) {
        return momentStore.listInteractions(onlyUnsynced, after);
    }

    public Path resolveImage(String mediaId) throws IOException {
        if (!SafeIds.isMediaId(mediaId)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(momentImageDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(mediaId + ".") && p.normalize().startsWith(momentImageDir);
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    public void clearAll() throws IOException {
        momentStore.clear();
        if (!Files.isDirectory(momentImageDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(momentImageDir)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best effort
                        }
                    });
        }
    }

    private long countImages() throws IOException {
        if (!Files.isDirectory(momentImageDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(momentImageDir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private static void requireIdentity(String userId, String userName) {
        if (!SafeIds.isSafeToken(userId)) {
            throw new IllegalArgumentException("user_id 非法");
        }
        if (!SafeIds.isSafeDisplayName(userName)) {
            throw new IllegalArgumentException("user_name 非法");
        }
    }
}

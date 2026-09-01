package com.wecom.bridge.contact;

import com.wecom.bridge.config.BridgeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 朋友圈互动数据（点赞数 / 评论数）的自动汇总与缓存。
 *
 * <p>官方没有「一次拿到所有互动数」的接口：要先列发表记录，再按发表成员逐条查。
 * 这里定时跑一遍并缓存，页面直接读缓存，避免每次打开都打一串接口。</p>
 */
@Service
public class MomentStatsService {

    private static final Logger log = LoggerFactory.getLogger(MomentStatsService.class);

    private final BridgeProperties properties;
    private final MomentsService momentsService;

    private final Map<String, MomentModels.MomentStats> statsByMoment = new ConcurrentHashMap<>();
    private final AtomicLong lastRefreshAt = new AtomicLong(0L);
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    private ScheduledExecutorService scheduler;

    public MomentStatsService(BridgeProperties properties, MomentsService momentsService) {
        this.properties = properties;
        this.momentsService = momentsService;
    }

    @PostConstruct
    void start() {
        BridgeProperties.Contact contact = properties.getContact();
        if (!momentsService.configured() || !contact.isMomentStatsAutoRefresh()) {
            log.info("朋友圈互动数据自动刷新未启用");
            return;
        }
        long intervalMillis = Math.max(contact.getMomentStatsInterval().toMillis(), 60_000L);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "moment-stats-refresher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::refreshQuietly, 10_000L, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("朋友圈互动数据自动刷新已启动，间隔 {} 分钟", intervalMillis / 60_000);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 拉取回看窗口内的朋友圈并逐条汇总互动数据。
     *
     * @return 本次汇总的朋友圈条数
     */
    public int refresh() {
        BridgeProperties.Contact contact = properties.getContact();
        long endTime = Instant.now().getEpochSecond();
        long startTime = endTime - Duration.ofDays(contact.getMomentLookbackDays()).toSeconds();

        // filter_type=2：企业发表与个人发表都要
        List<MomentModels.MomentSummary> moments = momentsService.listMoments(startTime, endTime, null, 2);
        for (MomentModels.MomentSummary summary : moments) {
            statsByMoment.put(summary.momentId(), collectFor(summary));
        }
        lastRefreshAt.set(System.currentTimeMillis());
        return moments.size();
    }

    /** 只刷新一条。 */
    public MomentModels.MomentStats refreshOne(String momentId, String creator) {
        MomentModels.MomentStats stats = momentsService.collectStats(momentId, creator);
        MomentModels.MomentStats existing = statsByMoment.get(momentId);
        MomentModels.MomentStats merged = new MomentModels.MomentStats(
                momentId,
                stats.creator() != null && !stats.creator().isBlank() ? stats.creator()
                        : (existing == null ? "" : existing.creator()),
                existing == null ? 0L : existing.createTime(),
                existing == null ? "" : existing.text(),
                stats.senderCount(), stats.likeCount(), stats.commentCount(),
                stats.customerLikeCount(), stats.customerCommentCount(),
                stats.comments(), System.currentTimeMillis(), null);
        statsByMoment.put(momentId, merged);
        return merged;
    }

    private MomentModels.MomentStats collectFor(MomentModels.MomentSummary summary) {
        try {
            MomentModels.MomentStats stats = momentsService.collectStats(summary.momentId(), summary.creator());
            return new MomentModels.MomentStats(
                    summary.momentId(), summary.creator(), summary.createTime(), summary.text(),
                    stats.senderCount(), stats.likeCount(), stats.commentCount(),
                    stats.customerLikeCount(), stats.customerCommentCount(),
                    stats.comments(), System.currentTimeMillis(), null);
        } catch (ContactApiException e) {
            log.info("汇总朋友圈互动数据失败 moment={}: {}", summary.momentId(), e.getMessage());
            return new MomentModels.MomentStats(
                    summary.momentId(), summary.creator(), summary.createTime(), summary.text(),
                    0, 0, 0, 0, 0, List.of(), System.currentTimeMillis(), e.getMessage());
        }
    }

    /** 按发表时间倒序返回缓存中的互动数据。 */
    public List<MomentModels.MomentStats> snapshot() {
        List<MomentModels.MomentStats> list = new ArrayList<>(statsByMoment.values());
        list.sort(Comparator.comparingLong(MomentModels.MomentStats::createTime).reversed());
        return list;
    }

    public long lastRefreshAt() {
        return lastRefreshAt.get();
    }

    public String lastError() {
        return lastError.get();
    }

    public Map<String, Object> overview() {
        List<MomentModels.MomentStats> list = snapshot();
        int likes = list.stream().mapToInt(MomentModels.MomentStats::likeCount).sum();
        int comments = list.stream().mapToInt(MomentModels.MomentStats::commentCount).sum();
        int customerLikes = list.stream().mapToInt(MomentModels.MomentStats::customerLikeCount).sum();
        int customerComments = list.stream().mapToInt(MomentModels.MomentStats::customerCommentCount).sum();
        return Map.of(
                "moment_count", list.size(),
                "like_count", likes,
                "comment_count", comments,
                "customer_like_count", customerLikes,
                "customer_comment_count", customerComments,
                "last_refresh_at", lastRefreshAt.get(),
                "last_error", lastError.get(),
                "lookback_days", properties.getContact().getMomentLookbackDays());
    }

    private void refreshQuietly() {
        try {
            int count = refresh();
            lastError.set("");
            log.debug("朋友圈互动数据已刷新，{} 条", count);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!message.equals(lastError.getAndSet(message))) {
                log.warn("刷新朋友圈互动数据失败: {}", message);
            }
        }
    }
}

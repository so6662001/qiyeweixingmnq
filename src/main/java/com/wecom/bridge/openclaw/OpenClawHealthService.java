package com.wecom.bridge.openclaw;

import com.wecom.bridge.config.BridgeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 定期探测 OpenClaw 网关与微信渠道是否还活着。
 *
 * <p>使用场景：人在外面用手机微信，收发靠机房/办公室那台机器上的网关。
 * 机器休眠、断网、渠道掉线时，页面上要能一眼看出来，而不是等客户投诉才发现。</p>
 */
@Service
public class OpenClawHealthService {

    private static final Logger log = LoggerFactory.getLogger(OpenClawHealthService.class);

    private final BridgeProperties properties;
    private final OpenClawGateway gateway;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.unknown());

    private ScheduledExecutorService scheduler;

    public OpenClawHealthService(BridgeProperties properties, OpenClawGateway gateway) {
        this.properties = properties;
        this.gateway = gateway;
    }

    @PostConstruct
    void start() {
        BridgeProperties.OpenClaw config = properties.getOpenclaw();
        if (!properties.isEnabled() || !config.isEnabled() || !config.isHealthCheckEnabled()) {
            log.info("OpenClaw 渠道存活探测未启用");
            return;
        }
        long intervalMillis = Math.max(config.getHealthCheckInterval().toMillis(), 15_000L);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "openclaw-health");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::checkQuietly, 5_000L, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("OpenClaw 渠道存活探测已启动，间隔 {} 秒", intervalMillis / 1000);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 立即探测一次。
     *
     * <p>只能证明两件事：网关 CLI 有响应、目标渠道出现在渠道列表里。
     * 这不等于微信一定能收发，但掉线时一定能测出来。</p>
     */
    public Snapshot check() {
        String channel = properties.getOpenclaw().getWechatChannel();
        String raw = gateway.probeChannels();
        long now = System.currentTimeMillis();

        if (raw == null || raw.isBlank()) {
            Snapshot result = new Snapshot(false, false, "网关无响应或未返回渠道列表", now);
            snapshot.set(result);
            return result;
        }

        boolean listed = raw.contains(channel);
        String detail = listed
                ? "渠道 " + channel + " 在列"
                : "网关有响应，但渠道 " + channel + " 不在列表里";
        Snapshot result = new Snapshot(true, listed, detail, now);
        snapshot.set(result);
        return result;
    }

    public Snapshot latest() {
        return snapshot.get();
    }

    /** 供页面显示的状态。 */
    public Map<String, Object> describe() {
        Snapshot current = snapshot.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.getOpenclaw().isHealthCheckEnabled());
        result.put("probe_ok", current.probeOk());
        result.put("channel_listed", current.channelListed());
        result.put("detail", current.detail());
        result.put("checked_at", current.checkedAt());
        result.put("stale", current.stale(properties.getOpenclaw().getHealthCheckInterval().toMillis()));
        return result;
    }

    private void checkQuietly() {
        try {
            Snapshot before = snapshot.get();
            Snapshot after = check();
            // 状态翻转时才打日志，避免正常运行时刷屏
            if (before.healthy() != after.healthy()) {
                if (after.healthy()) {
                    log.info("OpenClaw 渠道恢复：{}", after.detail());
                } else {
                    log.warn("OpenClaw 渠道异常：{}", after.detail());
                }
            }
        } catch (RuntimeException e) {
            log.debug("渠道存活探测异常: {}", e.getMessage());
        }
    }

    /**
     * @param probeOk       网关 CLI 是否有响应
     * @param channelListed 目标渠道是否在渠道列表里
     * @param checkedAt     探测时间；0 表示还没探测过
     */
    public record Snapshot(boolean probeOk, boolean channelListed, String detail, long checkedAt) {

        static Snapshot unknown() {
            return new Snapshot(false, false, "尚未探测", 0L);
        }

        public boolean healthy() {
            return probeOk && channelListed;
        }

        /** 超过两个探测周期没更新，说明探测本身也停了。 */
        public boolean stale(long intervalMillis) {
            if (checkedAt == 0L) {
                return true;
            }
            return System.currentTimeMillis() - checkedAt > intervalMillis * 2 + 10_000L;
        }
    }
}

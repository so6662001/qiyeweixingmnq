package com.wecom.bridge.archive;

import com.wecom.bridge.config.BridgeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话存档轮询：存档没有回调，只能按 seq 增量拉取。
 */
@Component
public class SessionArchivePoller {

    private static final Logger log = LoggerFactory.getLogger(SessionArchivePoller.class);

    private final BridgeProperties properties;
    private final SessionArchiveService archiveService;
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    private ScheduledExecutorService scheduler;

    public SessionArchivePoller(BridgeProperties properties, SessionArchiveService archiveService) {
        this.properties = properties;
        this.archiveService = archiveService;
    }

    @PostConstruct
    void start() {
        if (!archiveService.configured()) {
            log.info("会话存档未配置，跳过轮询");
            return;
        }
        long intervalMillis = Math.max(properties.getArchive().getPollInterval().toMillis(), 1000L);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wecom-archive-poller");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::pollQuietly, 2000L, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("会话存档轮询已启动，间隔 {} ms", intervalMillis);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void pollQuietly() {
        try {
            archiveService.pull();
            lastError.set("");
        } catch (ArchiveSdkException e) {
            // 常见原因：官方 SDK 未部署、私钥不匹配、Secret 失效。记录首因即可，避免刷屏
            String message = e.getMessage() == null ? "未知错误" : e.getMessage();
            if (!message.equals(lastError.getAndSet(message))) {
                log.warn("会话存档拉取失败: {}", message);
            }
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!message.equals(lastError.getAndSet(message))) {
                log.warn("会话存档拉取异常: {}", message);
            }
        }
    }

    public String lastError() {
        return lastError.get();
    }
}

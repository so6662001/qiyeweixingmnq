package com.wecom.bridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 官方通道配置。所有凭据建议通过环境变量注入，不要写入仓库。
 */
@ConfigurationProperties(prefix = "wecom.bridge")
public class BridgeProperties {

    /** 总开关：关闭时仅暴露状态接口，不调用任何外部接口。 */
    private boolean enabled = false;

    /** 企业 ID（corpid）。 */
    private String corpId = "";

    /** 官方接口域名，便于私有化/代理环境替换。 */
    private String apiBase = "https://qyapi.weixin.qq.com";

    /** 会话与游标持久化目录。 */
    private String dataDir = "data/bridge";

    /** 内存中保留的最大消息数（超出后按时间淘汰，磁盘同样裁剪）。 */
    private int maxMessages = 5000;

    /** 单次外部请求超时。 */
    private Duration requestTimeout = Duration.ofSeconds(10);

    /** 演示模式：无真实凭据时，把本地模拟器消息接入统一收件箱，便于先看效果。 */
    private boolean demoInbox = true;

    private final App app = new App();
    private final Kf kf = new Kf();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = corpId == null ? "" : corpId.trim();
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public boolean isDemoInbox() {
        return demoInbox;
    }

    public void setDemoInbox(boolean demoInbox) {
        this.demoInbox = demoInbox;
    }

    public App getApp() {
        return app;
    }

    public Kf getKf() {
        return kf;
    }

    /**
     * 企业内部应用：接收企业成员发给应用的消息，并用 message/send 回复。
     */
    public static class App {
        private boolean enabled = false;
        private long agentId = 0L;
        private String secret = "";
        private String callbackToken = "";
        private String callbackAesKey = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getAgentId() {
            return agentId;
        }

        public void setAgentId(long agentId) {
            this.agentId = agentId;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret == null ? "" : secret.trim();
        }

        public String getCallbackToken() {
            return callbackToken;
        }

        public void setCallbackToken(String callbackToken) {
            this.callbackToken = callbackToken == null ? "" : callbackToken.trim();
        }

        public String getCallbackAesKey() {
            return callbackAesKey;
        }

        public void setCallbackAesKey(String callbackAesKey) {
            this.callbackAesKey = callbackAesKey == null ? "" : callbackAesKey.trim();
        }

        public boolean isConfigured() {
            return enabled && !secret.isEmpty() && !callbackToken.isEmpty() && callbackAesKey.length() == 43;
        }
    }

    /**
     * 微信客服：接收微信用户消息（sync_msg）并回复（send_msg）。
     * 这是官方开放给企业、可与微信个人用户双向收发的合规通道。
     */
    public static class Kf {
        private boolean enabled = false;
        private String secret = "";
        private String callbackToken = "";
        private String callbackAesKey = "";

        /** 人工接待成员 UserID；配置后可自动把会话转为人工接待，便于在本系统直接回复。 */
        private String servicerUserid = "";

        /** 收到消息后自动把接待状态转为「由人工接待」。 */
        private boolean autoTakeOver = false;

        /** sync_msg 单次拉取条数上限（官方上限 1000）。 */
        private int syncLimit = 1000;

        /** 客户可回复窗口（官方规则：客户最后一条消息 48 小时内可主动发消息）。 */
        private Duration replyWindow = Duration.ofHours(48);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret == null ? "" : secret.trim();
        }

        public String getCallbackToken() {
            return callbackToken;
        }

        public void setCallbackToken(String callbackToken) {
            this.callbackToken = callbackToken == null ? "" : callbackToken.trim();
        }

        public String getCallbackAesKey() {
            return callbackAesKey;
        }

        public void setCallbackAesKey(String callbackAesKey) {
            this.callbackAesKey = callbackAesKey == null ? "" : callbackAesKey.trim();
        }

        public String getServicerUserid() {
            return servicerUserid;
        }

        public void setServicerUserid(String servicerUserid) {
            this.servicerUserid = servicerUserid == null ? "" : servicerUserid.trim();
        }

        public boolean isAutoTakeOver() {
            return autoTakeOver;
        }

        public void setAutoTakeOver(boolean autoTakeOver) {
            this.autoTakeOver = autoTakeOver;
        }

        public int getSyncLimit() {
            return syncLimit;
        }

        public void setSyncLimit(int syncLimit) {
            this.syncLimit = Math.min(Math.max(syncLimit, 1), 1000);
        }

        public Duration getReplyWindow() {
            return replyWindow;
        }

        public void setReplyWindow(Duration replyWindow) {
            this.replyWindow = replyWindow;
        }

        public boolean isConfigured() {
            return enabled && !secret.isEmpty() && !callbackToken.isEmpty() && callbackAesKey.length() == 43;
        }
    }
}

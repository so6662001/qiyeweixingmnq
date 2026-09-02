package com.wecom.bridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一收件箱配置。
 *
 * <p>入站：个人微信来自 OpenClaw 插件回推，企业微信来自会话内容存档 SDK。
 * 出站：两个通道都通过 OpenClaw 发送。凭据一律从环境变量注入。</p>
 */
@ConfigurationProperties(prefix = "wecom.bridge")
public class BridgeProperties {

    /** 总开关：关闭时只暴露状态接口，不做任何外部调用。 */
    private boolean enabled = false;

    /** 会话、消息与游标的持久化目录。 */
    private String dataDir = "data/bridge";

    /** 内存与快照中保留的最大消息数。 */
    private int maxMessages = 5000;

    /** 演示通道：无真实配置时也能验证「实时收 → 页面内回」链路。 */
    private boolean demoInbox = true;

    /**
     * 演练模式：所有出站动作（发消息、发朋友圈、群发）只记录不真发。
     * 真实环境首次联调建议先开着，确认参数无误后再关闭。
     */
    private boolean dryRun = false;

    private final Auth auth = new Auth();
    private final OpenClaw openclaw = new OpenClaw();
    private final Archive archive = new Archive();
    private final Contact contact = new Contact();

    public Auth getAuth() {
        return auth;
    }

    /**
     * 访问鉴权。收件箱能读到全部客户聊天、还能以你的身份发消息，
     * 一旦要从手机/外网访问就必须先过这一关。
     */
    public static class Auth {

        private boolean enabled = true;

        /**
         * 访问口令。**留空时只允许本机回环访问**，外部请求一律拒绝——
         * 这样「忘了配口令就把客户聊天暴露到公网」不会发生。
         */
        private String accessCode = "";

        /** 登录态有效期。 */
        private Duration sessionTtl = Duration.ofDays(7);

        /** 生产环境走 HTTPS 时置 true，Cookie 加 Secure 标记。 */
        private boolean cookieSecure = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAccessCode() {
            return accessCode;
        }

        public void setAccessCode(String accessCode) {
            this.accessCode = accessCode == null ? "" : accessCode.trim();
        }

        public Duration getSessionTtl() {
            return sessionTtl;
        }

        public void setSessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
        }

        public boolean isCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }

        /** 配了口令才能对外提供访问。 */
        public boolean hasAccessCode() {
            return !accessCode.isEmpty();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public boolean isDemoInbox() {
        return demoInbox;
    }

    public void setDemoInbox(boolean demoInbox) {
        this.demoInbox = demoInbox;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public OpenClaw getOpenclaw() {
        return openclaw;
    }

    public Archive getArchive() {
        return archive;
    }

    public Contact getContact() {
        return contact;
    }

    /** 客户联系未单独配置企业 ID 时，复用会话存档的。 */
    public String contactCorpId() {
        return contact.getCorpId().isBlank() ? archive.getCorpId() : contact.getCorpId();
    }

    /**
     * 企业微信「客户联系」：朋友圈发表与互动数据、客户群群发。
     *
     * <p>用客户联系 Secret 换 access_token，与会话存档的 Secret 不是同一个。</p>
     */
    public static class Contact {

        private boolean enabled = false;

        /** 企业 ID。留空时回落到 archive.corp-id。 */
        private String corpId = "";

        /** 「客户联系」Secret，或已配置到可调用应用列表的自建应用 Secret。 */
        private String secret = "";

        private String apiBase = "https://qyapi.weixin.qq.com";

        private Duration requestTimeout = Duration.ofSeconds(15);

        /** 朋友圈互动数据（点赞/评论）自动刷新间隔。 */
        private Duration momentStatsInterval = Duration.ofMinutes(10);

        /** 自动刷新时回看的天数，官方限制起止间隔不超过 30 天。 */
        private int momentLookbackDays = 7;

        /** 是否开启朋友圈互动数据自动轮询。 */
        private boolean momentStatsAutoRefresh = true;

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

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret == null ? "" : secret.trim();
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase == null || apiBase.isBlank() ? "https://qyapi.weixin.qq.com" : apiBase.trim();
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public Duration getMomentStatsInterval() {
            return momentStatsInterval;
        }

        public void setMomentStatsInterval(Duration momentStatsInterval) {
            this.momentStatsInterval = momentStatsInterval;
        }

        public int getMomentLookbackDays() {
            return momentLookbackDays;
        }

        public void setMomentLookbackDays(int momentLookbackDays) {
            this.momentLookbackDays = Math.min(Math.max(momentLookbackDays, 1), 30);
        }

        public boolean isMomentStatsAutoRefresh() {
            return momentStatsAutoRefresh;
        }

        public void setMomentStatsAutoRefresh(boolean momentStatsAutoRefresh) {
            this.momentStatsAutoRefresh = momentStatsAutoRefresh;
        }

        public boolean isConfigured() {
            return enabled && !secret.isBlank();
        }
    }

    /**
     * OpenClaw 网关：出站发送用 {@code openclaw message send}，入站由插件回推。
     */
    public static class OpenClaw {

        private boolean enabled = false;

        /** openclaw 可执行文件路径。 */
        private String cliPath = "openclaw";

        /** 个人微信渠道 id，腾讯官方插件注册的是 openclaw-weixin。 */
        private String wechatChannel = "openclaw-weixin";

        /** 多账号时指定 openclaw 账号 id，留空用默认账号。 */
        private String account = "";

        /** 单次发送命令超时。 */
        private Duration commandTimeout = Duration.ofSeconds(20);

        /** 插件回推入站消息时使用的共享密钥；为空则拒绝所有入站请求。 */
        private String inboundToken = "";

        /**
         * 定期探测网关与微信渠道是否还活着。
         * 人在外面、收发靠另一台机器时，这是判断「还能不能用」的依据。
         */
        private boolean healthCheckEnabled = true;

        private Duration healthCheckInterval = Duration.ofSeconds(60);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCliPath() {
            return cliPath;
        }

        public void setCliPath(String cliPath) {
            this.cliPath = cliPath == null || cliPath.isBlank() ? "openclaw" : cliPath.trim();
        }

        public String getWechatChannel() {
            return wechatChannel;
        }

        public void setWechatChannel(String wechatChannel) {
            this.wechatChannel = wechatChannel == null || wechatChannel.isBlank()
                    ? "openclaw-weixin" : wechatChannel.trim();
        }

        public String getAccount() {
            return account;
        }

        public void setAccount(String account) {
            this.account = account == null ? "" : account.trim();
        }

        public Duration getCommandTimeout() {
            return commandTimeout;
        }

        public void setCommandTimeout(Duration commandTimeout) {
            this.commandTimeout = commandTimeout;
        }

        public String getInboundToken() {
            return inboundToken;
        }

        public void setInboundToken(String inboundToken) {
            this.inboundToken = inboundToken == null ? "" : inboundToken.trim();
        }

        public boolean isHealthCheckEnabled() {
            return healthCheckEnabled;
        }

        public void setHealthCheckEnabled(boolean healthCheckEnabled) {
            this.healthCheckEnabled = healthCheckEnabled;
        }

        public Duration getHealthCheckInterval() {
            return healthCheckInterval;
        }

        public void setHealthCheckInterval(Duration healthCheckInterval) {
            this.healthCheckInterval = healthCheckInterval;
        }

        /** 出站可用（发送）。 */
        public boolean isOutboundReady() {
            return enabled && !cliPath.isBlank();
        }

        /** 入站可用（接收插件回推）。 */
        public boolean isInboundReady() {
            return enabled && !inboundToken.isBlank();
        }
    }

    /**
     * 企业微信会话内容存档：官方 SDK 轮询拉取，seq 游标持久化。
     */
    public static class Archive {

        private boolean enabled = false;

        /** 企业 ID。 */
        private String corpId = "";

        /** 「聊天内容存档」应用的 Secret。 */
        private String secret = "";

        /** 公钥版本号 → 对应的 PKCS#8 私钥 PEM。存档消息会带回 publickey_ver。 */
        private Map<String, String> privateKeys = new LinkedHashMap<>();

        /** libWeWorkFinanceSdk_Java.so 所在路径；留空则依赖 java.library.path。 */
        private String sdkLibraryPath = "";

        /** 轮询间隔。 */
        private Duration pollInterval = Duration.ofSeconds(5);

        /** 单次拉取条数，官方上限 1000。 */
        private int batchLimit = 1000;

        /** 出网代理，形如 socks5://10.0.0.1:8081，可留空。 */
        private String proxy = "";

        /** 代理账号密码，形如 user:passwd，可留空。 */
        private String proxyPasswd = "";

        /** SDK 请求超时秒数。 */
        private int requestTimeoutSeconds = 10;

        /**
         * 存档身份 → OpenClaw 发送目标 的映射。
         *
         * <p>存档里的 userid / external_userid 不等于 OpenClaw 的发送目标，
         * 需要显式映射；未配置的会话在页面上会提示补充映射。</p>
         */
        private Map<String, String> targetMapping = new LinkedHashMap<>();

        /**
         * 本企业成员 UserID 列表。用于判断存档里的一条消息是「客户发来的」还是「成员发出的」。
         * 留空时退化为按 external_userid 命名规则推断，配置后更准确。
         */
        private java.util.Set<String> memberUserids = new java.util.LinkedHashSet<>();

        /** 只把与外部联系人的消息接进收件箱。 */
        private boolean externalOnly = false;

        /** 是否接收群聊消息（roomid 非空）。默认只接一对一。 */
        private boolean includeRoomChats = false;

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

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret == null ? "" : secret.trim();
        }

        public Map<String, String> getPrivateKeys() {
            return privateKeys;
        }

        public void setPrivateKeys(Map<String, String> privateKeys) {
            this.privateKeys = privateKeys == null ? new LinkedHashMap<>() : privateKeys;
        }

        public String getSdkLibraryPath() {
            return sdkLibraryPath;
        }

        public void setSdkLibraryPath(String sdkLibraryPath) {
            this.sdkLibraryPath = sdkLibraryPath == null ? "" : sdkLibraryPath.trim();
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public int getBatchLimit() {
            return batchLimit;
        }

        public void setBatchLimit(int batchLimit) {
            this.batchLimit = Math.min(Math.max(batchLimit, 1), 1000);
        }

        public String getProxy() {
            return proxy;
        }

        public void setProxy(String proxy) {
            this.proxy = proxy == null ? "" : proxy.trim();
        }

        public String getProxyPasswd() {
            return proxyPasswd;
        }

        public void setProxyPasswd(String proxyPasswd) {
            this.proxyPasswd = proxyPasswd == null ? "" : proxyPasswd.trim();
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }

        public Map<String, String> getTargetMapping() {
            return targetMapping;
        }

        public void setTargetMapping(Map<String, String> targetMapping) {
            this.targetMapping = targetMapping == null ? new LinkedHashMap<>() : targetMapping;
        }

        public java.util.Set<String> getMemberUserids() {
            return memberUserids;
        }

        public void setMemberUserids(java.util.Set<String> memberUserids) {
            this.memberUserids = memberUserids == null ? new java.util.LinkedHashSet<>() : memberUserids;
        }

        public boolean isExternalOnly() {
            return externalOnly;
        }

        public void setExternalOnly(boolean externalOnly) {
            this.externalOnly = externalOnly;
        }

        public boolean isIncludeRoomChats() {
            return includeRoomChats;
        }

        public void setIncludeRoomChats(boolean includeRoomChats) {
            this.includeRoomChats = includeRoomChats;
        }

        public boolean isConfigured() {
            return enabled && !corpId.isBlank() && !secret.isBlank() && !privateKeys.isEmpty();
        }
    }
}

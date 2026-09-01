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

    private final OpenClaw openclaw = new OpenClaw();
    private final Archive archive = new Archive();

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

    public OpenClaw getOpenclaw() {
        return openclaw;
    }

    public Archive getArchive() {
        return archive;
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

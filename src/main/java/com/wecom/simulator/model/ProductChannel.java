package com.wecom.simulator.model;

/**
 * 模拟器产品通道：企业微信 / 个人微信，数据与媒体目录互相隔离。
 */
public enum ProductChannel {
    WECOM(
            "wecom",
            "企业微信模拟器",
            "/api",
            "/ws",
            "data/voices",
            "data/files",
            "data/moments",
            "group001",
            "销售协作群",
            "应用机器人",
            "wecom_moment",
            "ww_simulator"
    ),
    WECHAT(
            "wechat",
            "个人微信模拟器",
            "/api/wechat",
            "/ws/wechat",
            "data/wechat/voices",
            "data/wechat/files",
            "data/wechat/moments",
            "group001",
            "同学聚会群",
            "微信助手",
            "wechat_moment",
            "wx_simulator"
    );

    private final String id;
    private final String displayName;
    private final String apiBasePath;
    private final String wsPath;
    private final String voiceDir;
    private final String fileDir;
    private final String momentImageDir;
    private final String defaultGroupId;
    private final String defaultGroupName;
    private final String botDisplayName;
    private final String crmSource;
    private final String webhookToUser;

    ProductChannel(
            String id,
            String displayName,
            String apiBasePath,
            String wsPath,
            String voiceDir,
            String fileDir,
            String momentImageDir,
            String defaultGroupId,
            String defaultGroupName,
            String botDisplayName,
            String crmSource,
            String webhookToUser
    ) {
        this.id = id;
        this.displayName = displayName;
        this.apiBasePath = apiBasePath;
        this.wsPath = wsPath;
        this.voiceDir = voiceDir;
        this.fileDir = fileDir;
        this.momentImageDir = momentImageDir;
        this.defaultGroupId = defaultGroupId;
        this.defaultGroupName = defaultGroupName;
        this.botDisplayName = botDisplayName;
        this.crmSource = crmSource;
        this.webhookToUser = webhookToUser;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getApiBasePath() {
        return apiBasePath;
    }

    public String getWsPath() {
        return wsPath;
    }

    public String getVoiceDir() {
        return voiceDir;
    }

    public String getFileDir() {
        return fileDir;
    }

    public String getMomentImageDir() {
        return momentImageDir;
    }

    public String getDefaultGroupId() {
        return defaultGroupId;
    }

    public String getDefaultGroupName() {
        return defaultGroupName;
    }

    public String getBotDisplayName() {
        return botDisplayName;
    }

    public String getCrmSource() {
        return crmSource;
    }

    public String getWebhookToUser() {
        return webhookToUser;
    }

    public boolean isWechat() {
        return this == WECHAT;
    }
}

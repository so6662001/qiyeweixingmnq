package com.wecom.simulator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CrmConfigRequest {

    @NotBlank
    @Size(max = 2048)
    private String url;

    private boolean enabled = true;

    /** 可选：写入 CRM 时附带的 Authorization 头 */
    @Size(max = 512)
    private String authHeader;

    private boolean autoSync = true;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAuthHeader() {
        return authHeader;
    }

    public void setAuthHeader(String authHeader) {
        this.authHeader = authHeader;
    }

    public boolean isAutoSync() {
        return autoSync;
    }

    public void setAutoSync(boolean autoSync) {
        this.autoSync = autoSync;
    }
}

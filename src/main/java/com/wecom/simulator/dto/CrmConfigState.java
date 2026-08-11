package com.wecom.simulator.dto;

public class CrmConfigState {

    private String url;
    private boolean enabled;
    private boolean autoSync;
    private boolean authConfigured;

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

    public boolean isAutoSync() {
        return autoSync;
    }

    public void setAutoSync(boolean autoSync) {
        this.autoSync = autoSync;
    }

    public boolean isAuthConfigured() {
        return authConfigured;
    }

    public void setAuthConfigured(boolean authConfigured) {
        this.authConfigured = authConfigured;
    }
}

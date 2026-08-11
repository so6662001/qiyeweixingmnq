package com.wecom.simulator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class MomentLikeRequest {

    @NotBlank
    @Size(max = 64)
    private String userId = "lead001";

    @NotBlank
    @Size(max = 64)
    private String userName = "潜在客户甲";

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }
}

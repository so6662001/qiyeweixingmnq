package com.wecom.simulator.dto;

import com.wecom.simulator.model.MomentInteraction;

import java.util.ArrayList;
import java.util.List;

public class CrmSyncResult {

    private int total;
    private int success;
    private int failed;
    private List<MomentInteraction> items = new ArrayList<>();

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getSuccess() {
        return success;
    }

    public void setSuccess(int success) {
        this.success = success;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public List<MomentInteraction> getItems() {
        return items;
    }

    public void setItems(List<MomentInteraction> items) {
        this.items = items;
    }
}

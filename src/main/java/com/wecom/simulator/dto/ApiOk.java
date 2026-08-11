package com.wecom.simulator.dto;

import com.wecom.simulator.model.Message;

public class ApiOk {

    private boolean ok = true;
    private Message message;

    public static ApiOk of() {
        return new ApiOk();
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public Message getMessage() {
        return message;
    }

    public void setMessage(Message message) {
        this.message = message;
    }
}

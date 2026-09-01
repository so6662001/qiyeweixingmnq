package com.wecom.bridge.client;

/**
 * 官方接口返回非 0 errcode，或网络层失败。
 */
public class WecomApiException extends RuntimeException {

    private final int errcode;
    private final String errmsg;

    public WecomApiException(int errcode, String errmsg) {
        super("wecom api error " + errcode + ": " + errmsg);
        this.errcode = errcode;
        this.errmsg = errmsg;
    }

    public WecomApiException(String message, Throwable cause) {
        super(message, cause);
        this.errcode = -1;
        this.errmsg = message;
    }

    public int getErrcode() {
        return errcode;
    }

    public String getErrmsg() {
        return errmsg;
    }

    /** 42001/40014：access_token 过期或非法，可强制刷新后重试一次。 */
    public boolean isTokenInvalid() {
        return errcode == 42001 || errcode == 40014 || errcode == 40001;
    }
}

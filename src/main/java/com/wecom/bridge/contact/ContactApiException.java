package com.wecom.bridge.contact;

/**
 * 客户联系接口调用失败。
 */
public class ContactApiException extends RuntimeException {

    private final int errcode;

    public ContactApiException(int errcode, String errmsg) {
        super(describe(errcode, errmsg));
        this.errcode = errcode;
    }

    public ContactApiException(String message, Throwable cause) {
        super(message, cause);
        this.errcode = -1;
    }

    public ContactApiException(String message) {
        super(message);
        this.errcode = -1;
    }

    public int getErrcode() {
        return errcode;
    }

    /** 42001/40014：access_token 过期或非法，可强制刷新后重试一次。 */
    public boolean isTokenInvalid() {
        return errcode == 42001 || errcode == 40014 || errcode == 40001;
    }

    /**
     * 把常见错误码翻成可执行的中文提示，真实环境排查时少走弯路。
     */
    private static String describe(int errcode, String errmsg) {
        String hint = switch (errcode) {
            case 40003 -> "userid 不存在或不在应用可见范围";
            case 40050 -> "chat_id 无效（群已解散或群主离职）";
            case 41048 -> "群发频率超限：同一客户群每月可接收条数有限";
            case 45033 -> "接口并发调用超限，请降低频率";
            case 48002 -> "接口无权限：检查是否用「客户联系」Secret，或应用是否在可调用列表里";
            case 60011 -> "无权限操作该成员/客户，检查应用可见范围";
            case 84061 -> "不存在的发表任务或朋友圈";
            case 84062 -> "朋友圈内容为空或格式不正确";
            case 84063 -> "朋友圈可见范围为空";
            case 84064, 84065 -> "朋友圈发表任务超出频率限制";
            case 301002 -> "无权限调用，检查 Secret 与可调用应用配置";
            default -> null;
        };
        String base = "客户联系接口错误 " + errcode + (errmsg == null || errmsg.isBlank() ? "" : "：" + errmsg);
        return hint == null ? base : base + "（" + hint + "）";
    }
}

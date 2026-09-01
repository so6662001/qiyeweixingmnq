package com.wecom.bridge.crypto;

/**
 * 企业微信回调加解密失败。对应官方示例代码中的 AesException。
 */
public class CallbackCryptoException extends RuntimeException {

    public enum Reason {
        ILLEGAL_AES_KEY("EncodingAESKey 非法，必须为 43 位"),
        INVALID_SIGNATURE("msg_signature 校验失败"),
        DECRYPT_FAILED("回调密文解密失败"),
        ENCRYPT_FAILED("回调明文加密失败"),
        RECEIVE_ID_MISMATCH("密文中的 receiveid 与配置的 corpid 不一致"),
        PARSE_XML_FAILED("回调 XML 解析失败");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    private final Reason reason;

    public CallbackCryptoException(Reason reason) {
        super(reason.getMessage());
        this.reason = reason;
    }

    public CallbackCryptoException(Reason reason, Throwable cause) {
        super(reason.getMessage(), cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

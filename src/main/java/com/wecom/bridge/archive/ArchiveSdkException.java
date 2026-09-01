package com.wecom.bridge.archive;

/**
 * 会话存档 SDK 调用失败。
 */
public class ArchiveSdkException extends RuntimeException {

    private final int returnCode;

    public ArchiveSdkException(String message) {
        super(message);
        this.returnCode = -1;
    }

    public ArchiveSdkException(String message, Throwable cause) {
        super(message, cause);
        this.returnCode = -1;
    }

    public ArchiveSdkException(String message, int returnCode) {
        super(message + "（SDK 返回码 " + returnCode + "）");
        this.returnCode = returnCode;
    }

    public int getReturnCode() {
        return returnCode;
    }
}

package com.wecom.bridge.archive;

/**
 * 企业微信「会话内容存档」官方 SDK 的最小抽象。
 *
 * <p>官方 SDK 是本地动态库（{@code libWeWorkFinanceSdk_Java.so} + {@code com.tencent.wework.Finance}），
 * 有授权与分发限制，不随本仓库提供。这里只定义边界，运行时由
 * {@link NativeWeWorkFinanceSdk} 反射调用已部署的官方 SDK；测试用假实现。</p>
 */
public interface WeWorkFinanceSdk extends AutoCloseable {

    /** SDK 是否可用（动态库与类是否加载成功）。 */
    boolean available();

    /** 对应官方 NewSdk + Init。 */
    void init(String corpId, String secret);

    /**
     * 对应官方 GetChatData。
     *
     * @param seq   上次拉取到的最大 seq，首次传 0；返回的消息从 seq+1 开始
     * @param limit 单次条数，官方上限 1000
     * @return 原始 JSON 字符串，含 errcode / errmsg / chatdata
     */
    String getChatData(long seq, long limit, String proxy, String passwd, long timeoutSeconds);

    /**
     * 对应官方 DecryptData。
     *
     * @param encryptKey encrypt_random_key 经企业私钥 RSA 解密后的内容
     * @param encryptMsg 存档返回的 encrypt_chat_msg
     * @return 消息明文 JSON
     */
    String decryptData(String encryptKey, String encryptMsg);

    @Override
    void close();
}

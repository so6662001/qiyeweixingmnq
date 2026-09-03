package com.wecom.bridge.support;

import com.wecom.bridge.archive.ArchiveSdkException;
import com.wecom.bridge.archive.WeWorkFinanceSdk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用假会话存档 SDK：官方 SDK 是本地动态库，测试里用它替换。
 */
public class FakeWeWorkFinanceSdk implements WeWorkFinanceSdk {

    public record ChatDataCall(long seq, long limit) {
    }

    private final List<ChatDataCall> calls = new ArrayList<>();
    private final Map<String, String> plaintextByCipher = new HashMap<>();
    private final List<String> responses = new ArrayList<>();

    private boolean initialized;
    private String initCorpId;
    private String initSecret;
    private String expectedRandomKey;
    private int responseIndex;

    /** 预置一次 GetChatData 的返回。可多次调用形成序列，序列耗尽后返回空 chatdata。 */
    public FakeWeWorkFinanceSdk queueChatData(String json) {
        responses.add(json);
        return this;
    }

    /** 登记密文 → 明文，供 decryptData 使用。 */
    public FakeWeWorkFinanceSdk mapPlaintext(String encryptChatMsg, String plainJson) {
        plaintextByCipher.put(encryptChatMsg, plainJson);
        return this;
    }

    /** 断言 decryptData 收到的对称密钥与预期一致。 */
    public FakeWeWorkFinanceSdk expectRandomKey(String randomKey) {
        this.expectedRandomKey = randomKey;
        return this;
    }

    @Override
    public boolean available() {
        return initialized;
    }

    @Override
    public void init(String corpId, String secret) {
        this.initCorpId = corpId;
        this.initSecret = secret;
        this.initialized = true;
    }

    @Override
    public String getChatData(long seq, long limit, String proxy, String passwd, long timeoutSeconds) {
        calls.add(new ChatDataCall(seq, limit));
        if (responseIndex < responses.size()) {
            return responses.get(responseIndex++);
        }
        return "{\"errcode\":0,\"errmsg\":\"ok\",\"chatdata\":[]}";
    }

    @Override
    public String decryptData(String encryptKey, String encryptMsg) {
        if (expectedRandomKey != null && !expectedRandomKey.equals(encryptKey)) {
            throw new ArchiveSdkException("对称密钥不匹配，期望 " + expectedRandomKey + " 实际 " + encryptKey);
        }
        String plain = plaintextByCipher.get(encryptMsg);
        if (plain == null) {
            throw new ArchiveSdkException("未登记该密文的明文: " + encryptMsg);
        }
        return plain;
    }

    @Override
    public void close() {
        initialized = false;
    }

    public List<ChatDataCall> calls() {
        return calls;
    }

    public ChatDataCall lastCall() {
        if (calls.isEmpty()) {
            throw new IllegalStateException("没有调用过 GetChatData");
        }
        return calls.get(calls.size() - 1);
    }

    public String initCorpId() {
        return initCorpId;
    }

    public String initSecret() {
        return initSecret;
    }
}

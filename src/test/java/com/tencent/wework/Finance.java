package com.tencent.wework;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 官方 {@code com.tencent.wework.Finance} 的测试桩。
 *
 * <p>官方类是 JNI 声明（native 方法 + 本地动态库），有分发限制不能进仓库。
 * 这里用同名同签名的纯 Java 实现，验证
 * {@link com.wecom.bridge.archive.NativeWeWorkFinanceSdk} 的反射调用契约
 * （方法名、参数类型、slice 生命周期）是否正确。</p>
 */
public final class Finance {

    public record InitCall(long sdk, String corpId, String secret) {
    }

    public record ChatDataCall(long sdk, long seq, long limit, String proxy, String passwd, long timeout) {
    }

    public record DecryptCall(String encryptKey, String encryptMsg) {
    }

    private static final AtomicLong SDK_SEQ = new AtomicLong(1000);
    private static final AtomicLong SLICE_SEQ = new AtomicLong(1);
    private static final Map<Long, String> SLICE_CONTENT = new LinkedHashMap<>();

    private static final List<InitCall> INIT_CALLS = new ArrayList<>();
    private static final List<ChatDataCall> CHAT_DATA_CALLS = new ArrayList<>();
    private static final List<DecryptCall> DECRYPT_CALLS = new ArrayList<>();
    private static final List<Long> FREED_SLICES = new ArrayList<>();
    private static final List<Long> DESTROYED_SDKS = new ArrayList<>();

    private static String chatDataResponse = "{\"errcode\":0,\"errmsg\":\"ok\",\"chatdata\":[]}";
    private static String decryptResponse = "{\"msgid\":\"stub\"}";
    private static int initReturn;
    private static int chatDataReturn;
    private static int decryptReturn;

    private Finance() {
    }

    /* —— 官方签名 —— */

    public static long NewSdk() {
        return SDK_SEQ.incrementAndGet();
    }

    public static int Init(long sdk, String corpid, String secret) {
        INIT_CALLS.add(new InitCall(sdk, corpid, secret));
        return initReturn;
    }

    public static long NewSlice() {
        long slice = SLICE_SEQ.incrementAndGet();
        SLICE_CONTENT.put(slice, "");
        return slice;
    }

    public static void FreeSlice(long slice) {
        FREED_SLICES.add(slice);
        SLICE_CONTENT.remove(slice);
    }

    public static String GetContentFromSlice(long slice) {
        return SLICE_CONTENT.getOrDefault(slice, "");
    }

    public static int GetChatData(long sdk, long seq, long limit, String proxy, String passwd,
                                 long timeout, long chatData) {
        CHAT_DATA_CALLS.add(new ChatDataCall(sdk, seq, limit, proxy, passwd, timeout));
        SLICE_CONTENT.put(chatData, chatDataResponse);
        return chatDataReturn;
    }

    public static int DecryptData(String encryptKey, String encryptMsg, long msg) {
        DECRYPT_CALLS.add(new DecryptCall(encryptKey, encryptMsg));
        SLICE_CONTENT.put(msg, decryptResponse);
        return decryptReturn;
    }

    public static void DestroySdk(long sdk) {
        DESTROYED_SDKS.add(sdk);
    }

    /* —— 测试辅助 —— */

    public static void reset() {
        INIT_CALLS.clear();
        CHAT_DATA_CALLS.clear();
        DECRYPT_CALLS.clear();
        FREED_SLICES.clear();
        DESTROYED_SDKS.clear();
        SLICE_CONTENT.clear();
        initReturn = 0;
        chatDataReturn = 0;
        decryptReturn = 0;
        chatDataResponse = "{\"errcode\":0,\"errmsg\":\"ok\",\"chatdata\":[]}";
        decryptResponse = "{\"msgid\":\"stub\"}";
    }

    public static void setInitReturn(int value) {
        initReturn = value;
    }

    public static void setChatDataReturn(int value) {
        chatDataReturn = value;
    }

    public static void setDecryptReturn(int value) {
        decryptReturn = value;
    }

    public static void setChatDataResponse(String value) {
        chatDataResponse = value;
    }

    public static void setDecryptResponse(String value) {
        decryptResponse = value;
    }

    public static List<InitCall> initCalls() {
        return INIT_CALLS;
    }

    public static List<ChatDataCall> chatDataCalls() {
        return CHAT_DATA_CALLS;
    }

    public static List<DecryptCall> decryptCalls() {
        return DECRYPT_CALLS;
    }

    public static List<Long> freedSlices() {
        return FREED_SLICES;
    }

    public static List<Long> destroyedSdks() {
        return DESTROYED_SDKS;
    }

    public static int openSliceCount() {
        return SLICE_CONTENT.size();
    }
}

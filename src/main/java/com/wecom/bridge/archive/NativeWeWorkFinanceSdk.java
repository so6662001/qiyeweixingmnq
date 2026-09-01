package com.wecom.bridge.archive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * 反射调用官方 {@code com.tencent.wework.Finance}。
 *
 * <p>用反射而不是编译期依赖，是因为官方 SDK 的 jar 与 .so 需要企业自行下载部署，
 * 不能进代码库；缺失时本类只是「不可用」，不影响服务启动与其他通道。</p>
 *
 * <p>部署方式：把官方 {@code WeWorkFinanceSdk_Java.jar} 放进 classpath，
 * 把 {@code libWeWorkFinanceSdk_Java.so} 路径配到
 * {@code wecom.bridge.archive.sdk-library-path}（或放到 java.library.path）。</p>
 */
public class NativeWeWorkFinanceSdk implements WeWorkFinanceSdk {

    private static final Logger log = LoggerFactory.getLogger(NativeWeWorkFinanceSdk.class);
    private static final String FINANCE_CLASS = "com.tencent.wework.Finance";

    private final String libraryPath;

    private Class<?> financeClass;
    private long sdkHandle;
    private boolean loaded;

    public NativeWeWorkFinanceSdk(String libraryPath) {
        this.libraryPath = libraryPath == null ? "" : libraryPath.trim();
    }

    @Override
    public boolean available() {
        return loaded && financeClass != null && sdkHandle != 0L;
    }

    @Override
    public synchronized void init(String corpId, String secret) {
        if (available()) {
            return;
        }
        try {
            if (!libraryPath.isEmpty()) {
                System.load(libraryPath);
            }
            financeClass = Class.forName(FINANCE_CLASS);
        } catch (ClassNotFoundException e) {
            throw new ArchiveSdkException(
                    "未找到官方 SDK 类 " + FINANCE_CLASS + "，请把 WeWorkFinanceSdk_Java.jar 加入 classpath", e);
        } catch (UnsatisfiedLinkError | SecurityException e) {
            throw new ArchiveSdkException("加载官方 SDK 动态库失败: " + libraryPath, e);
        }

        try {
            sdkHandle = (long) invokeStatic("NewSdk");
            int ret = (int) invokeStatic("Init", new Class<?>[]{long.class, String.class, String.class},
                    sdkHandle, corpId, secret);
            if (ret != 0) {
                destroy();
                throw new ArchiveSdkException("会话存档 Init 失败", ret);
            }
            loaded = true;
            log.info("会话存档 SDK 初始化成功");
        } catch (ArchiveSdkException e) {
            throw e;
        } catch (Exception e) {
            throw new ArchiveSdkException("会话存档 SDK 初始化异常", e);
        }
    }

    @Override
    public String getChatData(long seq, long limit, String proxy, String passwd, long timeoutSeconds) {
        requireReady();
        long slice = 0L;
        try {
            slice = (long) invokeStatic("NewSlice");
            int ret = (int) invokeStatic("GetChatData",
                    new Class<?>[]{long.class, long.class, long.class, String.class, String.class, long.class, long.class},
                    sdkHandle, seq, limit, nullToEmpty(proxy), nullToEmpty(passwd), timeoutSeconds, slice);
            if (ret != 0) {
                throw new ArchiveSdkException("GetChatData 失败", ret);
            }
            return (String) invokeStatic("GetContentFromSlice", new Class<?>[]{long.class}, slice);
        } catch (ArchiveSdkException e) {
            throw e;
        } catch (Exception e) {
            throw new ArchiveSdkException("GetChatData 调用异常", e);
        } finally {
            freeSliceQuietly(slice);
        }
    }

    @Override
    public String decryptData(String encryptKey, String encryptMsg) {
        requireReady();
        long slice = 0L;
        try {
            slice = (long) invokeStatic("NewSlice");
            int ret = (int) invokeStatic("DecryptData",
                    new Class<?>[]{String.class, String.class, long.class},
                    encryptKey, encryptMsg, slice);
            if (ret != 0) {
                throw new ArchiveSdkException("DecryptData 失败", ret);
            }
            return (String) invokeStatic("GetContentFromSlice", new Class<?>[]{long.class}, slice);
        } catch (ArchiveSdkException e) {
            throw e;
        } catch (Exception e) {
            throw new ArchiveSdkException("DecryptData 调用异常", e);
        } finally {
            freeSliceQuietly(slice);
        }
    }

    @Override
    public synchronized void close() {
        destroy();
        loaded = false;
    }

    private void destroy() {
        if (financeClass == null || sdkHandle == 0L) {
            return;
        }
        try {
            invokeStatic("DestroySdk", new Class<?>[]{long.class}, sdkHandle);
        } catch (Exception e) {
            log.debug("DestroySdk 失败: {}", e.getMessage());
        } finally {
            sdkHandle = 0L;
        }
    }

    private void freeSliceQuietly(long slice) {
        if (slice == 0L || financeClass == null) {
            return;
        }
        try {
            invokeStatic("FreeSlice", new Class<?>[]{long.class}, slice);
        } catch (Exception e) {
            // slice 泄漏会累积内存，这里只能记录
            log.warn("FreeSlice 失败: {}", e.getMessage());
        }
    }

    private void requireReady() {
        if (!available()) {
            throw new ArchiveSdkException("会话存档 SDK 未就绪，请先 init");
        }
    }

    private Object invokeStatic(String name) throws Exception {
        return invokeStatic(name, new Class<?>[0]);
    }

    private Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = financeClass.getMethod(name, types);
        return method.invoke(null, args);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

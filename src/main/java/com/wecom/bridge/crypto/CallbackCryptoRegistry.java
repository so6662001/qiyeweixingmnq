package com.wecom.bridge.crypto;

import com.wecom.bridge.config.BridgeProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按通道缓存加解密器。凭据变更需重启进程，符合「凭据只从环境注入」的部署方式。
 */
@Component
public class CallbackCryptoRegistry {

    private final BridgeProperties properties;
    private final Map<String, CallbackCrypto> cache = new ConcurrentHashMap<>();

    public CallbackCryptoRegistry(BridgeProperties properties) {
        this.properties = properties;
    }

    public CallbackCrypto forWechatKf() {
        BridgeProperties.Kf kf = properties.getKf();
        if (!kf.isConfigured()) {
            throw new IllegalStateException("微信客服回调未配置完整（token / EncodingAESKey）");
        }
        return cache.computeIfAbsent("kf",
                key -> new CallbackCrypto(kf.getCallbackToken(), kf.getCallbackAesKey(), properties.getCorpId()));
    }

    public CallbackCrypto forWecomApp() {
        BridgeProperties.App app = properties.getApp();
        if (!app.isConfigured()) {
            throw new IllegalStateException("企微应用回调未配置完整（token / EncodingAESKey）");
        }
        return cache.computeIfAbsent("app",
                key -> new CallbackCrypto(app.getCallbackToken(), app.getCallbackAesKey(), properties.getCorpId()));
    }
}

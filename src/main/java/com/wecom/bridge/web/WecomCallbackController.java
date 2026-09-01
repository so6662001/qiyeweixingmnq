package com.wecom.bridge.web;

import com.wecom.bridge.crypto.CallbackCrypto;
import com.wecom.bridge.crypto.CallbackCryptoException;
import com.wecom.bridge.crypto.CallbackCryptoRegistry;
import com.wecom.bridge.service.WechatKfService;
import com.wecom.bridge.service.WecomAppService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 企业微信官方回调入口。
 *
 * <p>回调需在 5 秒内响应，因此这里只做验签与解密，实际拉取消息放到后台线程执行。</p>
 */
@RestController
@RequestMapping("/callback/wecom")
public class WecomCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WecomCallbackController.class);

    private final CallbackCryptoRegistry cryptoRegistry;
    private final WechatKfService wechatKfService;
    private final WecomAppService wecomAppService;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "wecom-callback-worker");
        thread.setDaemon(true);
        return thread;
    });

    public WecomCallbackController(CallbackCryptoRegistry cryptoRegistry,
                                   WechatKfService wechatKfService,
                                   WecomAppService wecomAppService) {
        this.cryptoRegistry = cryptoRegistry;
        this.wechatKfService = wechatKfService;
        this.wecomAppService = wecomAppService;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdown();
    }

    /**
     * 微信客服回调 URL 校验。
     */
    @GetMapping(value = "/kf", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verifyKf(@RequestParam("msg_signature") String msgSignature,
                                           @RequestParam("timestamp") String timestamp,
                                           @RequestParam("nonce") String nonce,
                                           @RequestParam("echostr") String echostr) {
        return verify(cryptoRegistry::forWechatKf, msgSignature, timestamp, nonce, echostr);
    }

    /**
     * 微信客服消息事件：解密后按 kf_msg_or_event 拉取消息。
     */
    @PostMapping(value = "/kf", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receiveKf(@RequestParam("msg_signature") String msgSignature,
                                            @RequestParam("timestamp") String timestamp,
                                            @RequestParam("nonce") String nonce,
                                            @RequestBody String body) {
        Map<String, String> fields;
        try {
            CallbackCrypto crypto = cryptoRegistry.forWechatKf();
            String plain = crypto.decryptMsg(msgSignature, timestamp, nonce, body);
            fields = CallbackCrypto.parseXml(plain);
        } catch (IllegalStateException e) {
            log.warn("微信客服回调未配置: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("");
        } catch (CallbackCryptoException e) {
            log.warn("微信客服回调解密失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body("");
        }

        String event = fields.getOrDefault("Event", "");
        String token = fields.getOrDefault("Token", "");
        String openKfid = fields.getOrDefault("OpenKfId", "");

        if ("kf_msg_or_event".equals(event)) {
            worker.submit(() -> {
                try {
                    wechatKfService.handleCallbackEvent(token, openKfid);
                } catch (RuntimeException e) {
                    log.warn("拉取微信客服消息失败: {}", e.getMessage());
                }
            });
        } else {
            log.debug("忽略微信客服事件: {}", event);
        }

        // 官方要求返回空串表示已接收
        return ResponseEntity.ok("");
    }

    /**
     * 企微自建应用回调 URL 校验。
     */
    @GetMapping(value = "/app", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verifyApp(@RequestParam("msg_signature") String msgSignature,
                                            @RequestParam("timestamp") String timestamp,
                                            @RequestParam("nonce") String nonce,
                                            @RequestParam("echostr") String echostr) {
        return verify(cryptoRegistry::forWecomApp, msgSignature, timestamp, nonce, echostr);
    }

    /**
     * 企微成员发给应用的消息。
     */
    @PostMapping(value = "/app", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receiveApp(@RequestParam("msg_signature") String msgSignature,
                                             @RequestParam("timestamp") String timestamp,
                                             @RequestParam("nonce") String nonce,
                                             @RequestBody String body) {
        Map<String, String> fields;
        try {
            CallbackCrypto crypto = cryptoRegistry.forWecomApp();
            String plain = crypto.decryptMsg(msgSignature, timestamp, nonce, body);
            fields = CallbackCrypto.parseXml(plain);
        } catch (IllegalStateException e) {
            log.warn("企微应用回调未配置: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("");
        } catch (CallbackCryptoException e) {
            log.warn("企微应用回调解密失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body("");
        }

        worker.submit(() -> {
            try {
                wecomAppService.handleCallback(fields);
            } catch (RuntimeException e) {
                log.warn("处理企微成员消息失败: {}", e.getMessage());
            }
        });
        return ResponseEntity.ok("");
    }

    private ResponseEntity<String> verify(CryptoSupplier supplier,
                                          String msgSignature,
                                          String timestamp,
                                          String nonce,
                                          String echostr) {
        try {
            String plain = supplier.get().verifyUrl(msgSignature, timestamp, nonce, echostr);
            return ResponseEntity.ok(plain);
        } catch (IllegalStateException e) {
            log.warn("回调校验失败（未配置）: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("");
        } catch (CallbackCryptoException e) {
            log.warn("回调校验失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body("");
        }
    }

    @FunctionalInterface
    private interface CryptoSupplier {
        CallbackCrypto get();
    }
}

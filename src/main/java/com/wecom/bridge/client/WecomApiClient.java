package com.wecom.bridge.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.config.BridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信官方接口客户端：负责 access_token 缓存、请求编码与 errcode 处理。
 *
 * <p>只调用官方开放接口（qyapi.weixin.qq.com），不涉及任何非官方协议。</p>
 */
@Component
public class WecomApiClient {

    private static final Logger log = LoggerFactory.getLogger(WecomApiClient.class);

    /** 提前过期时间，避免边界上使用到即将失效的 token。 */
    private static final long EXPIRY_SAFETY_MILLIS = 120_000L;

    private final BridgeProperties properties;
    private final HttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public WecomApiClient(BridgeProperties properties, HttpTransport transport, ObjectMapper objectMapper) {
        this.properties = properties;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    public JsonNode get(String path, String secret, Map<String, String> query) {
        return callWithTokenRetry(secret, token -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("access_token", token);
            if (query != null) {
                params.putAll(query);
            }
            return request("GET", path, params, null);
        });
    }

    public JsonNode post(String path, String secret, Object body) {
        return callWithTokenRetry(secret, token -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("access_token", token);
            return request("POST", path, params, writeJson(body));
        });
    }

    private JsonNode callWithTokenRetry(String secret, TokenCall call) {
        String token = accessToken(secret, false);
        try {
            return call.apply(token);
        } catch (WecomApiException e) {
            if (!e.isTokenInvalid()) {
                throw e;
            }
            log.info("access_token 失效，强制刷新后重试一次");
            return call.apply(accessToken(secret, true));
        }
    }

    /**
     * 获取并缓存 access_token。官方限制其有效期约 7200 秒，且需复用避免频繁调用。
     */
    public String accessToken(String secret, boolean forceRefresh) {
        if (secret == null || secret.isBlank()) {
            throw new WecomApiException(-1, "缺少应用 secret，请检查配置");
        }
        String corpId = properties.getCorpId();
        if (corpId.isBlank()) {
            throw new WecomApiException(-1, "缺少 corp-id，请检查配置");
        }

        CachedToken cached = tokenCache.get(secret);
        long now = System.currentTimeMillis();
        if (!forceRefresh && cached != null && cached.expiresAt() - EXPIRY_SAFETY_MILLIS > now) {
            return cached.token();
        }

        synchronized (tokenCache) {
            cached = tokenCache.get(secret);
            now = System.currentTimeMillis();
            if (!forceRefresh && cached != null && cached.expiresAt() - EXPIRY_SAFETY_MILLIS > now) {
                return cached.token();
            }

            Map<String, String> params = new LinkedHashMap<>();
            params.put("corpid", corpId);
            params.put("corpsecret", secret);
            JsonNode response = request("GET", "/cgi-bin/gettoken", params, null);

            String token = response.path("access_token").asText("");
            long expiresIn = response.path("expires_in").asLong(7200L);
            if (token.isBlank()) {
                throw new WecomApiException(-1, "gettoken 未返回 access_token");
            }
            tokenCache.put(secret, new CachedToken(token, System.currentTimeMillis() + expiresIn * 1000L));
            log.info("已刷新 access_token，有效期 {} 秒", expiresIn);
            return token;
        }
    }

    public void invalidateTokens() {
        tokenCache.clear();
    }

    private JsonNode request(String method, String path, Map<String, String> query, String body) {
        String url = properties.getApiBase() + path + encodeQuery(query);
        String raw;
        try {
            raw = transport.send(method, url, body);
        } catch (IOException e) {
            throw new WecomApiException("调用官方接口失败: " + path, e);
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (IOException e) {
            throw new WecomApiException("解析官方接口响应失败: " + path, e);
        }

        int errcode = node.path("errcode").asInt(0);
        if (errcode != 0) {
            throw new WecomApiException(errcode, node.path("errmsg").asText(""));
        }
        return node;
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body == null ? Map.of() : body);
        } catch (IOException e) {
            throw new WecomApiException("序列化请求体失败", e);
        }
    }

    private static String encodeQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private record CachedToken(String token, long expiresAt) {
    }

    @FunctionalInterface
    private interface TokenCall {
        JsonNode apply(String token);
    }
}

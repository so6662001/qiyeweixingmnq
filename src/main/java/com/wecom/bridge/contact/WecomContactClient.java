package com.wecom.bridge.contact;

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
import java.util.concurrent.atomic.AtomicReference;

/**
 * 企业微信「客户联系」接口客户端：access_token 缓存、errcode 处理、素材上传。
 */
@Component
public class WecomContactClient {

    private static final Logger log = LoggerFactory.getLogger(WecomContactClient.class);

    /** 提前过期时间，避免边界上用到即将失效的 token。 */
    private static final long EXPIRY_SAFETY_MILLIS = 120_000L;

    private final BridgeProperties properties;
    private final HttpTransport transport;
    private final ObjectMapper objectMapper;
    private final AtomicReference<CachedToken> token = new AtomicReference<>();

    public WecomContactClient(BridgeProperties properties, HttpTransport transport, ObjectMapper objectMapper) {
        this.properties = properties;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    public boolean configured() {
        return properties.isEnabled()
                && properties.getContact().isConfigured()
                && !properties.contactCorpId().isBlank();
    }

    public void requireConfigured() {
        if (!properties.isEnabled()) {
            throw new ContactApiException("统一收件箱总开关未启用（wecom.bridge.enabled）");
        }
        if (properties.contactCorpId().isBlank()) {
            throw new ContactApiException("缺少企业 ID（wecom.bridge.contact.corp-id 或 archive.corp-id）");
        }
        if (!properties.getContact().isConfigured()) {
            throw new ContactApiException("客户联系未配置：需要 wecom.bridge.contact.enabled 与 secret");
        }
    }

    public JsonNode get(String path, Map<String, String> query) {
        return withTokenRetry(accessToken -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("access_token", accessToken);
            if (query != null) {
                params.putAll(query);
            }
            return request("GET", path, params, null);
        });
    }

    public JsonNode post(String path, Object body) {
        return withTokenRetry(accessToken -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("access_token", accessToken);
            return request("POST", path, params, writeJson(body));
        });
    }

    /**
     * 上传临时素材，返回 media_id（官方有效期 3 天）。
     *
     * @param type image / voice / video / file
     */
    public String uploadMedia(String type, String fileName, String contentType, byte[] content) {
        JsonNode response = withTokenRetry(accessToken -> {
            String url = properties.getContact().getApiBase() + "/cgi-bin/media/upload"
                    + encodeQuery(Map.of("access_token", accessToken, "type", type));
            String raw;
            try {
                raw = transport.upload(url, "media", fileName, contentType, content);
            } catch (IOException e) {
                throw new ContactApiException("上传素材失败: " + fileName, e);
            }
            return parseAndCheck(raw, "/cgi-bin/media/upload");
        });
        String mediaId = response.path("media_id").asText("");
        if (mediaId.isBlank()) {
            throw new ContactApiException("素材上传未返回 media_id");
        }
        return mediaId;
    }

    public String accessToken(boolean forceRefresh) {
        requireConfigured();
        CachedToken cached = token.get();
        long now = System.currentTimeMillis();
        if (!forceRefresh && cached != null && cached.expiresAt() - EXPIRY_SAFETY_MILLIS > now) {
            return cached.value();
        }

        synchronized (token) {
            cached = token.get();
            now = System.currentTimeMillis();
            if (!forceRefresh && cached != null && cached.expiresAt() - EXPIRY_SAFETY_MILLIS > now) {
                return cached.value();
            }

            Map<String, String> params = new LinkedHashMap<>();
            params.put("corpid", properties.contactCorpId());
            params.put("corpsecret", properties.getContact().getSecret());
            JsonNode response = request("GET", "/cgi-bin/gettoken", params, null);

            String value = response.path("access_token").asText("");
            long expiresIn = response.path("expires_in").asLong(7200L);
            if (value.isBlank()) {
                throw new ContactApiException("gettoken 未返回 access_token");
            }
            token.set(new CachedToken(value, System.currentTimeMillis() + expiresIn * 1000L));
            log.info("客户联系 access_token 已刷新，有效期 {} 秒", expiresIn);
            return value;
        }
    }

    private JsonNode withTokenRetry(TokenCall call) {
        String accessToken = accessToken(false);
        try {
            return call.apply(accessToken);
        } catch (ContactApiException e) {
            if (!e.isTokenInvalid()) {
                throw e;
            }
            log.info("access_token 失效，强制刷新后重试一次");
            return call.apply(accessToken(true));
        }
    }

    private JsonNode request(String method, String path, Map<String, String> query, String body) {
        String url = properties.getContact().getApiBase() + path + encodeQuery(query);
        String raw;
        try {
            raw = transport.send(method, url, body);
        } catch (IOException e) {
            throw new ContactApiException("调用客户联系接口失败: " + path, e);
        }
        return parseAndCheck(raw, path);
    }

    private JsonNode parseAndCheck(String raw, String path) {
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (IOException e) {
            throw new ContactApiException("解析接口响应失败: " + path, e);
        }
        int errcode = node.path("errcode").asInt(0);
        if (errcode != 0) {
            throw new ContactApiException(errcode, node.path("errmsg").asText(""));
        }
        return node;
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body == null ? Map.of() : body);
        } catch (IOException e) {
            throw new ContactApiException("序列化请求体失败", e);
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

    private record CachedToken(String value, long expiresAt) {
    }

    @FunctionalInterface
    private interface TokenCall {
        JsonNode apply(String accessToken);
    }
}

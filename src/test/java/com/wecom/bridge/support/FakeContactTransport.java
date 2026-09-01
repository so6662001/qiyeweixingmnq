package com.wecom.bridge.support;

import com.wecom.bridge.contact.HttpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 测试用假客户联系 HTTP：按 URL 片段匹配响应，并记录请求便于断言。
 */
public class FakeContactTransport implements HttpTransport {

    public record Call(String method, String url, String body) {
    }

    public record Upload(String url, String fieldName, String fileName, String contentType, int size) {
    }

    private final Map<String, Function<Call, String>> handlers = new LinkedHashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private final List<Upload> uploads = new ArrayList<>();

    private String uploadResponse = "{\"errcode\":0,\"errmsg\":\"ok\",\"type\":\"image\",\"media_id\":\"MEDIA-1\"}";

    public FakeContactTransport() {
        respond("/cgi-bin/gettoken",
                "{\"errcode\":0,\"errmsg\":\"ok\",\"access_token\":\"contact-token\",\"expires_in\":7200}");
    }

    public FakeContactTransport respond(String urlFragment, String json) {
        handlers.put(urlFragment, call -> json);
        return this;
    }

    public FakeContactTransport on(String urlFragment, Function<Call, String> handler) {
        handlers.put(urlFragment, handler);
        return this;
    }

    public FakeContactTransport uploadResponds(String json) {
        this.uploadResponse = json;
        return this;
    }

    @Override
    public String send(String method, String url, String body) throws IOException {
        calls.add(new Call(method, url, body));
        for (Map.Entry<String, Function<Call, String>> entry : handlers.entrySet()) {
            if (url.contains(entry.getKey())) {
                return entry.getValue().apply(new Call(method, url, body));
            }
        }
        throw new IOException("未配置的请求: " + url);
    }

    @Override
    public String upload(String url, String fieldName, String fileName, String contentType, byte[] content) {
        uploads.add(new Upload(url, fieldName, fileName, contentType, content.length));
        return uploadResponse;
    }

    public List<Call> calls() {
        return calls;
    }

    public List<Call> callsTo(String urlFragment) {
        return calls.stream().filter(call -> call.url().contains(urlFragment)).toList();
    }

    public Call lastCallTo(String urlFragment) {
        List<Call> matched = callsTo(urlFragment);
        if (matched.isEmpty()) {
            throw new IllegalStateException("没有匹配的请求: " + urlFragment);
        }
        return matched.get(matched.size() - 1);
    }

    public List<Upload> uploads() {
        return uploads;
    }

    public void reset() {
        calls.clear();
        uploads.clear();
    }
}

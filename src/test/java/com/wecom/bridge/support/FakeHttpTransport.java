package com.wecom.bridge.support;

import com.wecom.bridge.client.HttpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 测试用假 HTTP 实现：按 URL 片段匹配响应，并记录每次请求便于断言。
 */
public class FakeHttpTransport implements HttpTransport {

    public record Call(String method, String url, String body) {
    }

    private final Map<String, Function<Call, String>> handlers = new LinkedHashMap<>();
    private final List<Call> calls = new ArrayList<>();

    public FakeHttpTransport() {
        // 默认提供 access_token，避免每个用例重复配置
        on("/cgi-bin/gettoken", call -> "{\"errcode\":0,\"errmsg\":\"ok\",\"access_token\":\"token-1\",\"expires_in\":7200}");
    }

    public FakeHttpTransport on(String urlFragment, Function<Call, String> handler) {
        handlers.put(urlFragment, handler);
        return this;
    }

    public FakeHttpTransport respond(String urlFragment, String json) {
        return on(urlFragment, call -> json);
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

    public void reset() {
        calls.clear();
    }
}

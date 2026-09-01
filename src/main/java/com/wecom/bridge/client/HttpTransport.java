package com.wecom.bridge.client;

import java.io.IOException;

/**
 * 最小 HTTP 抽象，便于在测试中替换掉真实网络调用。
 */
public interface HttpTransport {

    /**
     * @param method GET 或 POST
     * @param url    完整地址
     * @param body   POST 请求体（GET 传 null）
     * @return 响应体字符串
     */
    String send(String method, String url, String body) throws IOException;
}

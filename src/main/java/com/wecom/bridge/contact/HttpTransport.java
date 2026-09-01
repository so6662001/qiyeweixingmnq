package com.wecom.bridge.contact;

import java.io.IOException;

/**
 * 最小 HTTP 抽象，便于在测试中替换掉真实网络调用。
 */
public interface HttpTransport {

    /**
     * @param method GET 或 POST
     * @param url    完整地址
     * @param body   POST 的 JSON 请求体（GET 传 null）
     */
    String send(String method, String url, String body) throws IOException;

    /**
     * multipart 上传单个文件，用于企业微信素材上传。
     *
     * @param fieldName 表单字段名，官方为 media
     * @param fileName  文件名
     * @param content   文件内容
     */
    String upload(String url, String fieldName, String fileName, String contentType, byte[] content) throws IOException;
}

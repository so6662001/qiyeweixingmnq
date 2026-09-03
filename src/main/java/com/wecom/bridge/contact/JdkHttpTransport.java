package com.wecom.bridge.contact;

import com.wecom.bridge.config.BridgeProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Component
public class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;
    private final Duration timeout;

    public JdkHttpTransport(BridgeProperties properties) {
        this.timeout = properties.getContact().getRequestTimeout();
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String send(String method, String url, String body) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8");

        HttpRequest request = "POST".equalsIgnoreCase(method)
                ? builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8)).build()
                : builder.GET().build();

        return execute(request);
    }

    @Override
    public String upload(String url, String fieldName, String fileName, String contentType, byte[] content)
            throws IOException {
        String boundary = "----WecomBridge" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        buffer.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.write(("Content-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.write(content);
        buffer.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(buffer.toByteArray()))
                .build();

        return execute(request);
    }

    private String execute(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("unexpected http status " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("request interrupted", e);
        }
    }
}

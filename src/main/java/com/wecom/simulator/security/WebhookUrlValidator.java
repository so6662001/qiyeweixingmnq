package com.wecom.simulator.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * 限制 Webhook 目标，降低 SSRF 风险。
 * 默认仅允许回环地址；可通过配置放宽到私网（仍禁止链路本地/元数据地址）。
 */
@Component
public class WebhookUrlValidator {

    private final boolean allowPrivateNetwork;
    private final Set<String> allowedHosts;

    public WebhookUrlValidator(
            @Value("${wecom.simulator.webhook.allow-private-network:false}") boolean allowPrivateNetwork,
            @Value("${wecom.simulator.webhook.allowed-hosts:localhost,127.0.0.1,::1}") String allowedHosts
    ) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.allowedHosts = Set.of(allowedHosts.split("\\s*,\\s*"));
    }

    public String validateAndNormalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Webhook URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Webhook URL 格式非法");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Webhook 仅允许 http/https");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Webhook URL 不允许包含用户信息");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL 缺少主机名");
        }

        String hostLower = host.toLowerCase(Locale.ROOT);
        boolean hostAllowlisted = allowedHosts.stream().anyMatch(h -> h.equalsIgnoreCase(hostLower));

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("Webhook 主机无法解析: " + host);
        }
        if (addresses.length == 0) {
            throw new IllegalArgumentException("Webhook 主机无法解析: " + host);
        }

        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("Webhook 目标地址被拒绝: " + address.getHostAddress());
            }
            boolean loopback = address.isLoopbackAddress();
            boolean privateNet = address.isSiteLocalAddress();
            if (!loopback && !(allowPrivateNetwork && privateNet) && !hostAllowlisted) {
                throw new IllegalArgumentException(
                        "Webhook 默认仅允许本机回环地址；如需私网请配置 wecom.simulator.webhook.allow-private-network=true"
                );
            }
            if (!loopback && !privateNet && !hostAllowlisted) {
                throw new IllegalArgumentException("Webhook 目标不在允许范围: " + host);
            }
        }

        return uri.toString();
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || address.isLinkLocalAddress()) {
            return true;
        }
        // 云元数据常见地址
        String ip = address.getHostAddress();
        return "169.254.169.254".equals(ip) || "metadata.google.internal".equalsIgnoreCase(address.getHostName());
    }
}

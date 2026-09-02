package com.wecom.bridge.security;

import com.wecom.bridge.config.BridgeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 访问鉴权。
 *
 * <p>收件箱能读到全部客户聊天记录、还能以你的身份发消息，所以一旦从手机或外网访问，
 * 必须先过口令。策略：</p>
 * <ul>
 *   <li>配了口令 → 一律要登录（本机也要，行为一致）</li>
 *   <li>没配口令 → <b>只放行本机回环</b>，外部请求直接拒绝并说明原因</li>
 * </ul>
 *
 * <p>插件回推接口 {@code /api/openclaw/inbound} 有自己的共享密钥，不走这里。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AccessAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessAuthFilter.class);

    public static final String COOKIE_NAME = "wecom_bridge_session";

    /** 无需登录即可访问的路径。 */
    private static final Set<String> PUBLIC_EXACT = Set.of(
            "/login", "/login/", "/login/index.html",
            "/api/auth/login", "/api/auth/state", "/api/auth/logout",
            "/favicon.ico"
    );

    private static final List<String> PUBLIC_PREFIX = List.of(
            // 插件回推自带共享密钥
            "/api/openclaw/inbound",
            // 登录页要用的静态资源
            "/login/"
    );

    private final BridgeProperties properties;
    private final AccessTokenCodec tokenCodec;

    public AccessAuthFilter(BridgeProperties properties, AccessTokenCodec tokenCodec) {
        this.properties = properties;
        this.tokenCodec = tokenCodec;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!properties.getAuth().isEnabled() || isPublic(request)) {
            chain.doFilter(request, response);
            return;
        }

        if (properties.getAuth().hasAccessCode()) {
            if (authenticated(request)) {
                chain.doFilter(request, response);
            } else {
                reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "需要登录");
            }
            return;
        }

        // 没配口令：只有本机能用
        if (isLoopback(request)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("拒绝来自 {} 的访问：未配置访问口令", request.getRemoteAddr());
        reject(request, response, HttpServletResponse.SC_FORBIDDEN,
                "未配置访问口令，只允许本机访问。要从手机或外网使用，请先设置 WECOM_ACCESS_CODE 并重启。");
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (PUBLIC_EXACT.contains(path)) {
            return true;
        }
        return PUBLIC_PREFIX.stream().anyMatch(path::startsWith);
    }

    private boolean authenticated(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName()) && tokenCodec.valid(cookie.getValue())) {
                    return true;
                }
            }
        }
        // 便于脚本与自动化：也接受 Bearer 形式的口令
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return tokenCodec.matchesAccessCode(authorization.substring(7).trim());
        }
        return false;
    }

    /**
     * 判断是否本机请求。反向代理场景下 remoteAddr 是代理地址，
     * 所以真要对外暴露必须配口令，不能指望这个判断。
     */
    private boolean isLoopback(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null) {
            return false;
        }
        if ("127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote)) {
            return true;
        }
        try {
            return InetAddress.getByName(remote).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String message)
            throws IOException {
        // 页面请求跳登录页，接口请求返回 JSON，方便前端区分处理
        if (status == HttpServletResponse.SC_UNAUTHORIZED && wantsHtml(request)) {
            response.sendRedirect("/login");
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"ok\":false,\"error\":\""
                + message.replace("\"", "'") + "\",\"login_required\":true}");
        response.getWriter().flush();
    }

    private boolean wantsHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        boolean acceptsHtml = accept != null && accept.contains("text/html");
        boolean isApi = request.getRequestURI().startsWith("/api/");
        return acceptsHtml && !isApi;
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

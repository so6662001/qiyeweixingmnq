package com.wecom.bridge.config;

import com.wecom.bridge.web.InboxWebSocketHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableConfigurationProperties(BridgeProperties.class)
public class BridgeConfiguration implements WebSocketConfigurer, WebMvcConfigurer {

    private final InboxWebSocketHandler inboxWebSocketHandler;

    public BridgeConfiguration(InboxWebSocketHandler inboxWebSocketHandler) {
        this.inboxWebSocketHandler = inboxWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(inboxWebSocketHandler, "/ws/inbox").setAllowedOrigins("*");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/inbox").setViewName("forward:/inbox/index.html");
        registry.addViewController("/inbox/").setViewName("forward:/inbox/index.html");
        registry.addViewController("/ops").setViewName("forward:/ops/index.html");
        registry.addViewController("/ops/").setViewName("forward:/ops/index.html");
        registry.addViewController("/login").setViewName("forward:/login/index.html");
        registry.addViewController("/login/").setViewName("forward:/login/index.html");
    }
}

package com.wecom.simulator.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.simulator.model.ProductChannel;
import com.wecom.simulator.runtime.ChannelRuntime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

@Component
public class SimulatorWebSocketHandler extends TextWebSocketHandler {

    public static final String ATTR_CHANNEL = "productChannel";

    private final RealtimeHub realtimeHub;
    private final ChannelRuntime wecomRuntime;
    private final ChannelRuntime wechatRuntime;
    private final ObjectMapper objectMapper;

    public SimulatorWebSocketHandler(
            RealtimeHub realtimeHub,
            ChannelRuntime wecomRuntime,
            @Qualifier("wechatRuntime") ChannelRuntime wechatRuntime,
            ObjectMapper objectMapper
    ) {
        this.realtimeHub = realtimeHub;
        this.wecomRuntime = wecomRuntime;
        this.wechatRuntime = wechatRuntime;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        ProductChannel channel = resolveChannel(session);
        session.getAttributes().put(ATTR_CHANNEL, channel);
        realtimeHub.register(channel, session);
        ChannelRuntime runtime = channel == ProductChannel.WECHAT ? wechatRuntime : wecomRuntime;
        String payload = objectMapper.writeValueAsString(Map.of(
                "type", "snapshot",
                "session", runtime.getMessageStore().snapshot()
        ));
        session.sendMessage(new TextMessage(payload));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 心跳 ping，忽略内容
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ProductChannel channel = (ProductChannel) session.getAttributes().get(ATTR_CHANNEL);
        if (channel == null) {
            channel = ProductChannel.WECOM;
        }
        realtimeHub.unregister(channel, session);
    }

    private static ProductChannel resolveChannel(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        if (path != null && path.startsWith("/ws/wechat")) {
            return ProductChannel.WECHAT;
        }
        return ProductChannel.WECOM;
    }
}

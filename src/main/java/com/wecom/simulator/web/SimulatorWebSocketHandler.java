package com.wecom.simulator.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.simulator.store.MessageStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

@Component
public class SimulatorWebSocketHandler extends TextWebSocketHandler {

    private final RealtimeHub realtimeHub;
    private final MessageStore messageStore;
    private final ObjectMapper objectMapper;

    public SimulatorWebSocketHandler(
            RealtimeHub realtimeHub,
            MessageStore messageStore,
            ObjectMapper objectMapper
    ) {
        this.realtimeHub = realtimeHub;
        this.messageStore = messageStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        realtimeHub.register(session);
        String payload = objectMapper.writeValueAsString(Map.of(
                "type", "snapshot",
                "session", messageStore.snapshot()
        ));
        session.sendMessage(new TextMessage(payload));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 心跳 ping，忽略内容
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        realtimeHub.unregister(session);
    }
}

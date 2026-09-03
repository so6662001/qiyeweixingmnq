package com.wecom.bridge.web;

import com.wecom.bridge.service.InboxService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收件箱 WebSocket：连接后先下发一份快照，后续增量推送新消息与发送结果。
 */
@Component
public class InboxWebSocketHandler extends TextWebSocketHandler {

    private final InboxRealtimeHub hub;
    private final InboxService inboxService;

    public InboxWebSocketHandler(InboxRealtimeHub hub, InboxService inboxService) {
        this.hub = hub;
        this.inboxService = inboxService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        hub.register(session);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", inboxService.status());
        payload.put("conversations", inboxService.conversations());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "snapshot");
        event.put("payload", payload);
        hub.send(session, event);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 仅用于心跳，忽略内容
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.unregister(session);
    }
}

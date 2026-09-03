package com.wecom.bridge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 收件箱实时推送：新消息、发送结果、状态变化都通过这里下发，页面无需轮询。
 */
@Component
public class InboxRealtimeHub {

    private static final Logger log = LoggerFactory.getLogger(InboxRealtimeHub.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public InboxRealtimeHub(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(WebSocketSession session) {
        sessions.add(session);
    }

    public void unregister(WebSocketSession session) {
        sessions.remove(session);
    }

    public int connectionCount() {
        return sessions.size();
    }

    public void broadcast(String type, Object payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("payload", payload);
        broadcast(event);
    }

    public void broadcast(Map<String, ?> event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (IOException e) {
            log.warn("序列化收件箱事件失败", e);
            return;
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                sessions.remove(session);
                try {
                    session.close();
                } catch (IOException ignored) {
                    // 忽略关闭异常
                }
            }
        }
    }

    public void send(WebSocketSession session, Map<String, ?> event) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
            }
        } catch (IOException e) {
            log.debug("单发收件箱事件失败: {}", e.getMessage());
        }
    }
}

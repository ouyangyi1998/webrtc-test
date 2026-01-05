package com.example.webrtc.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class RoomRegistry {
    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public synchronized boolean addToRoom(String roomId, WebSocketSession session) {
        Set<WebSocketSession> sessions = rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());

        // 检查是否有同名用户（如果有，踢掉旧的）
        String newSender = (String) session.getAttributes().get("sender");
        if (newSender != null) {
            WebSocketSession duplicate = null;
            for (WebSocketSession existing : sessions) {
                String existingSender = (String) existing.getAttributes().get("sender");
                if (newSender.equals(existingSender) && !existing.getId().equals(session.getId())) {
                    duplicate = existing;
                    break;
                }
            }
            if (duplicate != null) {
                try {
                    // 关闭旧连接（状态码 4001: Duplicate Login）
                    duplicate.close(new org.springframework.web.socket.CloseStatus(4001, "Duplicate Login"));
                    sessions.remove(duplicate);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        if (sessions.size() >= 2) {
            return false;
        }
        sessions.add(session);
        session.getAttributes().put("roomId", roomId);
        return true;
    }

    public void remove(WebSocketSession session) {
        Object roomIdObj = session.getAttributes().get("roomId");
        if (roomIdObj == null) {
            return;
        }
        String roomId = roomIdObj.toString();
        Set<WebSocketSession> sessions = rooms.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                rooms.remove(roomId);
            }
        }
    }

    public Set<WebSocketSession> peersInRoom(String roomId, WebSocketSession exclude) {
        Set<WebSocketSession> sessions = rooms.get(roomId);
        if (sessions == null) {
            return Collections.emptySet();
        }
        return sessions.stream()
                .filter(s -> exclude == null || !s.getId().equals(exclude.getId()))
                .collect(Collectors.toSet());
    }
}

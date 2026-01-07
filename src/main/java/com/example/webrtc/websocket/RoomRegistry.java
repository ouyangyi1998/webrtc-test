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

        // 调试日志：当前房间状态
        System.out.println("[RoomRegistry] 房间 " + roomId + " 当前 session 数量: " + sessions.size());
        for (WebSocketSession s : sessions) {
            System.out.println("[RoomRegistry]   - session " + s.getId() + ", sender=" + s.getAttributes().get("sender")
                    + ", isOpen=" + s.isOpen());
        }

        // 清理无效的 session（已关闭的连接）
        int beforeSize = sessions.size();
        sessions.removeIf(s -> !s.isOpen());
        int afterSize = sessions.size();
        if (beforeSize != afterSize) {
            System.out.println("[RoomRegistry] 清理了 " + (beforeSize - afterSize) + " 个已关闭的 session");
        }

        // 检查是否有同名用户（如果有，踢掉旧的）
        String newSender = (String) session.getAttributes().get("sender");
        System.out.println("[RoomRegistry] 新用户尝试加入: sender=" + newSender + ", sessionId=" + session.getId());

        // 检查当前 session 是否已经在房间中（防止重复 join）
        if (sessions.contains(session)) {
            System.out.println("[RoomRegistry] 该 session 已在房间中，跳过重复加入");
            return true;
        }

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
                System.out.println("[RoomRegistry] 发现同名用户，踢掉旧 session: " + duplicate.getId());
                // 先从 sessions 中移除，再尝试关闭
                sessions.remove(duplicate);
                try {
                    // 关闭旧连接（状态码 4001: Duplicate Login）
                    duplicate.close(new org.springframework.web.socket.CloseStatus(4001, "Duplicate Login"));
                } catch (Exception e) {
                    // 忽略关闭错误，session 已经从集合中移除
                    e.printStackTrace();
                }
            }
        }

        if (sessions.size() >= 2) {
            System.out.println("[RoomRegistry] 房间已满，拒绝加入");
            return false;
        }
        sessions.add(session);
        session.getAttributes().put("roomId", roomId);
        System.out.println("[RoomRegistry] 成功加入房间，当前人数: " + sessions.size());
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

    /**
     * 获取所有房间的所有 session（用于超时检测）
     */
    public Set<WebSocketSession> getAllSessions() {
        return rooms.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
    }
}

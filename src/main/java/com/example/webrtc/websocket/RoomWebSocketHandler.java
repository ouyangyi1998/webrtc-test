package com.example.webrtc.websocket;

import com.example.webrtc.model.SignalMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RoomWebSocketHandler.class);

    private final RoomRegistry roomRegistry;
    private final ObjectMapper objectMapper;

    public RoomWebSocketHandler(RoomRegistry roomRegistry, ObjectMapper objectMapper) {
        this.roomRegistry = roomRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        SignalMessage signal = objectMapper.readValue(message.getPayload(), SignalMessage.class);
        if (!StringUtils.hasText(signal.getType())) {
            log.warn("收到无效消息: {}", message.getPayload());
            return;
        }

        if ("join".equalsIgnoreCase(signal.getType())) {
            handleJoin(session, signal);
            return;
        }
        if ("leave".equalsIgnoreCase(signal.getType())) {
            handleLeave(session);
            return;
        }
        // 心跳响应
        if ("ping".equalsIgnoreCase(signal.getType())) {
            session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            return;
        }

        String roomId = (String) session.getAttributes().get("roomId");
        if (!StringUtils.hasText(roomId)) {
            sendError(session, "未加入房间，无法发送信令");
            return;
        }

        forwardToPeers(roomId, session, signal);
    }

    private void handleJoin(WebSocketSession session, SignalMessage signal) throws IOException {
        String roomId = signal.getRoomId() == null ? "" : signal.getRoomId();
        String sender = signal.getSender() == null ? "" : signal.getSender();
        // #region agent log
        try (java.io.FileWriter fw = new java.io.FileWriter(
                "/Users/ouyangyi/Downloads/开发材料/开发软件/webrtc/.cursor/debug.log", true)) {
            fw.write(
                    "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"H4\",\"location\":\"RoomWebSocketHandler:handleJoin:entry\",\"message\":\"handleJoin entry\",\"data\":{\"roomId\":\""
                            + roomId + "\",\"sender\":\"" + sender + "\"},\"timestamp\":" + System.currentTimeMillis()
                            + "}\n");
        } catch (Exception ignored) {
        }
        // #endregion
        if (!StringUtils.hasText(roomId) || !StringUtils.hasText(sender)) {
            // #region agent log
            try (java.io.FileWriter fw = new java.io.FileWriter(
                    "/Users/ouyangyi/Downloads/开发材料/开发软件/webrtc/.cursor/debug.log", true)) {
                fw.write(
                        "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"H5\",\"location\":\"RoomWebSocketHandler:handleJoin:validationFailed\",\"message\":\"missing roomId or sender\",\"data\":{\"roomId\":\""
                                + roomId + "\",\"sender\":\"" + sender + "\"},\"timestamp\":"
                                + System.currentTimeMillis() + "}\n");
            } catch (Exception ignored) {
            }
            // #endregion
            sendError(session, "roomId 和 sender 必填");
            return;
        }

        session.getAttributes().put("sender", sender);
        boolean joined = roomRegistry.addToRoom(roomId, session);
        if (!joined) {
            sendError(session, "房间已满 (最多 2 人)");
            session.close(Objects.requireNonNull(CloseStatus.POLICY_VIOLATION));
            return;
        }
        log.info("session {} 加入房间 {}", session.getId(), roomId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("participants", roomRegistry.peersInRoom(roomId, null).size());
        SignalMessage ack = new SignalMessage("join-ack", roomId, "server", payload);
        String ackJson = Objects.requireNonNull(objectMapper.writeValueAsString(ack));
        session.sendMessage(new TextMessage(ackJson));

        SignalMessage notice = new SignalMessage("peer-joined", roomId, sender, null);
        forwardToPeers(roomId, session, notice);
    }

    private void handleLeave(WebSocketSession session) throws IOException {
        String roomId = (String) session.getAttributes().get("roomId");
        if (!StringUtils.hasText(roomId)) {
            return;
        }
        sendPeerLeft(session, roomId);
        session.getAttributes().put("leftNotified", true);
        roomRegistry.remove(session);
        session.close();
    }

    private void forwardToPeers(String roomId, WebSocketSession source, SignalMessage signal) {
        Set<WebSocketSession> peers = roomRegistry.peersInRoom(roomId, source);
        if (peers.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = Objects.requireNonNull(objectMapper.writeValueAsString(signal));
        } catch (JsonProcessingException e) {
            log.error("序列化消息失败", e);
            return;
        }
        for (WebSocketSession peer : peers) {
            if (peer.isOpen()) {
                try {
                    peer.sendMessage(new TextMessage(payload));
                } catch (IOException e) {
                    log.error("发送消息到 {} 失败", peer.getId(), e);
                }
            }
        }
    }

    private void sendError(WebSocketSession session, String msg) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", msg);
        SignalMessage error = new SignalMessage("error", null, "server", payload);
        String errorJson = Objects.requireNonNull(objectMapper.writeValueAsString(error));
        session.sendMessage(new TextMessage(errorJson));
    }

    private void sendPeerLeft(WebSocketSession session, String roomId) throws IOException {
        String sender = (String) session.getAttributes().getOrDefault("sender", "peer");
        SignalMessage left = new SignalMessage("peer-left", roomId, sender, null);
        forwardToPeers(roomId, session, left);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        String roomId = (String) session.getAttributes().get("roomId");
        if (StringUtils.hasText(roomId) && !Boolean.TRUE.equals(session.getAttributes().get("leftNotified"))) {
            try {
                sendPeerLeft(session, roomId);
                session.getAttributes().put("leftNotified", true);
            } catch (IOException e) {
                log.warn("通知房间 {} 成员离开失败", roomId, e);
            }
        }
        roomRegistry.remove(session);
    }
}

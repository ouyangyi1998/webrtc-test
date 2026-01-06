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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RoomWebSocketHandler.class);

    // 延迟发送 peer-left 的时间（毫秒），给客户端重连的缓冲期
    private static final long PEER_LEFT_DELAY_MS = 3000;

    private final RoomRegistry roomRegistry;
    private final ObjectMapper objectMapper;

    // 用于延迟发送 peer-left 的调度器
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 待发送的 peer-left 任务（key: roomId + ":" + sender）
    private final Map<String, ScheduledFuture<?>> pendingPeerLeftTasks = new ConcurrentHashMap<>();

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
        if (!StringUtils.hasText(roomId) || !StringUtils.hasText(sender)) {
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

        // 取消该用户之前可能待发送的 peer-left（用户快速重连的情况）
        String taskKey = roomId + ":" + sender;
        ScheduledFuture<?> pendingTask = pendingPeerLeftTasks.remove(taskKey);
        if (pendingTask != null) {
            pendingTask.cancel(false);
            log.info("用户 {} 重连，取消待发送的 peer-left", sender);
        }

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
        String sender = (String) session.getAttributes().getOrDefault("sender", "peer");

        // 先从房间移除该 session
        roomRegistry.remove(session);

        if (StringUtils.hasText(roomId) && !Boolean.TRUE.equals(session.getAttributes().get("leftNotified"))) {
            // 延迟发送 peer-left，给客户端重连的缓冲期
            String taskKey = roomId + ":" + sender;

            // 取消之前可能存在的同一用户的待发送任务
            ScheduledFuture<?> existing = pendingPeerLeftTasks.remove(taskKey);
            if (existing != null) {
                existing.cancel(false);
            }

            // 创建延迟任务
            ScheduledFuture<?> task = scheduler.schedule(() -> {
                // 3秒后检查用户是否已重连
                Set<WebSocketSession> currentPeers = roomRegistry.peersInRoom(roomId, null);
                boolean userReconnected = currentPeers.stream()
                        .anyMatch(s -> sender.equals(s.getAttributes().get("sender")));

                if (!userReconnected) {
                    // 用户未重连，发送 peer-left 给房间内其他成员
                    log.info("用户 {} 离开房间 {}（延迟确认）", sender, roomId);
                    SignalMessage left = new SignalMessage("peer-left", roomId, sender, null);
                    try {
                        String payload = objectMapper.writeValueAsString(left);
                        for (WebSocketSession peer : currentPeers) {
                            if (peer.isOpen()) {
                                try {
                                    peer.sendMessage(new TextMessage(payload));
                                } catch (IOException e) {
                                    log.warn("发送 peer-left 到 {} 失败", peer.getId(), e);
                                }
                            }
                        }
                    } catch (JsonProcessingException e) {
                        log.error("序列化 peer-left 失败", e);
                    }
                } else {
                    log.info("用户 {} 已重连房间 {}，跳过 peer-left", sender, roomId);
                }
                pendingPeerLeftTasks.remove(taskKey);
            }, PEER_LEFT_DELAY_MS, TimeUnit.MILLISECONDS);

            pendingPeerLeftTasks.put(taskKey, task);
            log.debug("计划延迟发送 peer-left: {} ({}ms后)", taskKey, PEER_LEFT_DELAY_MS);
        }
    }
}

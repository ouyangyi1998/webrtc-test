package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AgentClient extends WebSocketClient {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String roomId;
    private final String name;
    private final ControlHandler controlHandler;
    private final StatusListener listener;
    private WebRTCManager webRTCManager;
    
    // 重连相关
    private volatile boolean shouldReconnect = true;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_DELAY = 10000; // 最大10秒
    private ScheduledExecutorService reconnectExecutor;

    public interface StatusListener {
        void onStatus(String s);
    }

    public AgentClient(URI serverUri, String roomId, String name, ControlHandler controlHandler, StatusListener listener) {
        super(serverUri);
        this.roomId = roomId;
        this.name = name;
        this.controlHandler = controlHandler;
        this.listener = listener;
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WS-Reconnect-Thread");
            t.setDaemon(true);
            return t;
        });
    }

    public void setWebRTCManager(WebRTCManager manager) {
        this.webRTCManager = manager;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        // 重连成功，重置计数
        reconnectAttempts = 0;
        listener.onStatus("WebSocket 已连接");
        try {
            send(mapper.writeValueAsString(
                    mapper.createObjectNode()
                            .put("type", "join")
                            .put("roomId", roomId)
                            .put("sender", name)
            ));
        } catch (Exception e) {
            listener.onStatus("Send join failed: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode root = mapper.readTree(message);
            String type = root.path("type").asText("");
            
            // 房间信令消息
            if ("join-ack".equalsIgnoreCase(type)) {
                int participants = root.path("data").path("participants").asInt(1);
                listener.onStatus("加入房间成功，在线人数: " + participants);
                if (participants > 1) {
                    listener.onStatus("等待控制端发起连接...");
                }
            } else if ("peer-joined".equalsIgnoreCase(type)) {
                listener.onStatus("检测到控制端加入，等待连接...");
                // 被控端不主动创建 offer，等待控制端发起
            } else if ("peer-left".equalsIgnoreCase(type)) {
                listener.onStatus("控制端已离开，等待新的控制端连接...");
                if (webRTCManager != null) {
                    // 只清理RTC连接，保持WebSocket连接
                    webRTCManager.cleanupRtcOnly();
                }
            // WebRTC 信令消息
            } else if ("offer".equalsIgnoreCase(type)) {
                if (webRTCManager != null) {
                    JsonNode sdp = root.path("data").path("sdp");
                    webRTCManager.handleOffer(sdp);
                }
            } else if ("answer".equalsIgnoreCase(type)) {
                if (webRTCManager != null) {
                    JsonNode sdp = root.path("data").path("sdp");
                    webRTCManager.handleAnswer(sdp);
                }
            } else if ("candidate".equalsIgnoreCase(type)) {
                if (webRTCManager != null) {
                    JsonNode candidate = root.path("data").path("candidate");
                    webRTCManager.handleIceCandidate(candidate);
                }
            } else if ("control".equalsIgnoreCase(type)) {
                JsonNode data = root.path("data");
                String kind = data.path("kind").asText("");
                if ("mouse".equals(kind)) {
                    controlHandler.handleMouse(data);
                } else if ("keyboard".equals(kind)) {
                    controlHandler.handleKeyboard(data);
                } else if ("chat".equals(kind)) {
                    listener.onStatus("Chat: " + data.path("text").asText(""));
                }
            } else if ("stream_config".equalsIgnoreCase(type)) {
                // 处理视频流配置
                if (webRTCManager != null) {
                    JsonNode data = root.path("data");
                    webRTCManager.handleStreamConfig(data);
                }
            }
        } catch (Exception e) {
            listener.onStatus("Parse error: " + e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        listener.onStatus("WebSocket 已断开: " + reason);
        if (webRTCManager != null) {
            webRTCManager.cleanup();
        }
        
        // 自动重连（非手动断开且是远端断开时）
        if (shouldReconnect && remote) {
            scheduleReconnect();
        }
    }
    
    /**
     * 调度自动重连（指数退避策略）
     */
    private void scheduleReconnect() {
        if (reconnectExecutor == null || reconnectExecutor.isShutdown()) {
            listener.onStatus("重连执行器已关闭，无法重连");
            return;
        }
        
        int delay = Math.min(1000 * (int) Math.pow(2, reconnectAttempts), MAX_RECONNECT_DELAY);
        reconnectAttempts++;
        listener.onStatus("将在 " + delay + "ms 后尝试重连... (第" + reconnectAttempts + "次)");
        
        reconnectExecutor.schedule(() -> {
            if (!shouldReconnect) {
                listener.onStatus("重连已取消");
                return;
            }
            try {
                listener.onStatus("正在重连...");
                reconnect();
            } catch (Exception e) {
                listener.onStatus("重连失败: " + e.getMessage());
                // 继续尝试重连
                if (shouldReconnect) {
                    scheduleReconnect();
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onError(Exception ex) {
        listener.onStatus("WS error: " + ex.getMessage());
    }

    // 发送 WebRTC 信令消息
    public void sendSignal(String type, JsonNode data) {
        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("type", type);
            message.put("roomId", roomId);
            message.put("sender", name);
            message.set("data", data);
            send(mapper.writeValueAsString(message));
        } catch (Exception e) {
            listener.onStatus("发送信令失败: " + e.getMessage());
        }
    }
    
    /**
     * 手动停止连接并禁用自动重连
     */
    public void stopAndDisconnect() {
        listener.onStatus("手动断开连接...");
        shouldReconnect = false;
        if (reconnectExecutor != null && !reconnectExecutor.isShutdown()) {
            reconnectExecutor.shutdownNow();
        }
        try {
            close();
        } catch (Exception e) {
            listener.onStatus("关闭连接异常: " + e.getMessage());
        }
    }
    
    /**
     * 启用自动重连
     */
    public void enableReconnect() {
        shouldReconnect = true;
        reconnectAttempts = 0;
        if (reconnectExecutor == null || reconnectExecutor.isShutdown()) {
            reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "WS-Reconnect-Thread");
                t.setDaemon(true);
                return t;
            });
        }
    }
}

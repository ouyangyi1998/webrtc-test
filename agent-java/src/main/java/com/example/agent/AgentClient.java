package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

class AgentClient extends WebSocketClient {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String roomId;
    private final String name;
    private final ControlHandler controlHandler;
    private final StatusListener listener;
    private WebRTCManager webRTCManager;

    interface StatusListener {
        void onStatus(String s);
    }

    AgentClient(URI serverUri, String roomId, String name, ControlHandler controlHandler, StatusListener listener) {
        super(serverUri);
        this.roomId = roomId;
        this.name = name;
        this.controlHandler = controlHandler;
        this.listener = listener;
    }

    void setWebRTCManager(WebRTCManager manager) {
        this.webRTCManager = manager;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        listener.onStatus("WS connected");
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
                listener.onStatus("控制端已离开");
                if (webRTCManager != null) {
                    webRTCManager.cleanup();
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
            }
        } catch (Exception e) {
            listener.onStatus("Parse error: " + e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        listener.onStatus("WS closed: " + reason);
        if (webRTCManager != null) {
            webRTCManager.cleanup();
        }
    }

    @Override
    public void onError(Exception ex) {
        listener.onStatus("WS error: " + ex.getMessage());
    }

    // 发送 WebRTC 信令消息
    void sendSignal(String type, JsonNode data) {
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
}

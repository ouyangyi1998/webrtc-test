package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.video.VideoTrack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WebRTCManager {
    
    /**
     * 聊天消息监听器接口
     */
    public interface ChatListener {
        void onChatMessage(String sender, String message);
        void onDataChannelStateChange(boolean isOpen);
    }
    
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentClient client;
    private final AgentClient.StatusListener listener;
    private final String[] stunUrls;
    private final String[] turnUrls;
    private final String turnUser;
    private final String turnPass;
    private final ControlHandler controlHandler;
    
    private ChatListener chatListener;

    private PeerConnectionFactory factory;
    private RTCPeerConnection peerConnection;
    private ScreenCaptureSource screenSource;
    private RTCDataChannel dataChannel;
    private RTCRtpSender videoSender;
    private int currentTargetBitrate = 2000000;
    
    /**
     * 设置聊天消息监听器
     */
    public void setChatListener(ChatListener listener) {
        this.chatListener = listener;
    }

    public WebRTCManager(AgentClient client, AgentClient.StatusListener listener,
                  String[] stunUrls, String[] turnUrls, String turnUser, String turnPass,
                  ControlHandler controlHandler) {
        this.client = client;
        this.listener = listener;
        this.stunUrls = stunUrls;
        this.turnUrls = turnUrls;
        this.turnUser = turnUser;
        this.turnPass = turnPass;
        this.controlHandler = controlHandler;
    }

    public void init() {
        try {
            listener.onStatus("初始化 WebRTC...");
            
            // 初始化 PeerConnectionFactory
            factory = new PeerConnectionFactory();
            
            // 配置 ICE 服务器
            List<RTCIceServer> iceServers = new ArrayList<>();
            
            // 添加 STUN 服务器
            if (stunUrls != null) {
                for (String url : stunUrls) {
                    if (url != null && !url.trim().isEmpty()) {
                        RTCIceServer stunServer = new RTCIceServer();
                        stunServer.urls.add(url.trim());
                        iceServers.add(stunServer);
                    }
                }
            }
            
            // 添加 TURN 服务器
            if (turnUrls != null) {
                for (String url : turnUrls) {
                    if (url != null && !url.trim().isEmpty()) {
                        RTCIceServer turnServer = new RTCIceServer();
                        turnServer.urls.add(url.trim());
                        turnServer.username = turnUser;
                        turnServer.password = turnPass;
                        iceServers.add(turnServer);
                    }
                }
            }
            
            // 创建 RTCConfiguration
            RTCConfiguration config = new RTCConfiguration();
            config.iceServers = iceServers;
            config.iceTransportPolicy = RTCIceTransportPolicy.ALL;
            config.bundlePolicy = RTCBundlePolicy.BALANCED;
            config.rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE;
            
            // 创建 PeerConnection
            peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    listener.onStatus("ICE 候选: " + candidate.sdp);
                    try {
                        ObjectNode candidateJson = mapper.createObjectNode();
                        candidateJson.put("candidate", candidate.sdp);
                        candidateJson.put("sdpMid", candidate.sdpMid);
                        candidateJson.put("sdpMLineIndex", candidate.sdpMLineIndex);
                        
                        ObjectNode data = mapper.createObjectNode();
                        data.set("candidate", candidateJson);
                        
                        client.sendSignal("candidate", data);
                    } catch (Exception e) {
                        listener.onStatus("发送 ICE 候选失败: " + e.getMessage());
                    }
                }

                @Override
                public void onIceGatheringChange(RTCIceGatheringState state) {
                    listener.onStatus("ICE 收集状态: " + state);
                }

                @Override
                public void onIceConnectionChange(RTCIceConnectionState state) {
                    listener.onStatus("ICE 连接状态: " + state);
                    
                    // ICE连接失败时尝试重启
                    if (state == RTCIceConnectionState.FAILED) {
                        listener.onStatus("ICE连接失败，尝试重启...");
                        try {
                            if (peerConnection != null) {
                                peerConnection.restartIce();
                                listener.onStatus("ICE重启已触发");
                            }
                        } catch (Exception e) {
                            listener.onStatus("ICE重启失败: " + e.getMessage());
                        }
                    }
                }

                @Override
                public void onConnectionChange(RTCPeerConnectionState state) {
                    listener.onStatus("连接状态: " + state);
                }

                @Override
                public void onSignalingChange(RTCSignalingState state) {
                    listener.onStatus("信令状态: " + state);
                }

                @Override
                public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
                    listener.onStatus("收到远端轨道");
                }

                @Override
                public void onRemoveTrack(RTCRtpReceiver receiver) {
                    listener.onStatus("远端轨道移除");
                }

                @Override
                public void onDataChannel(RTCDataChannel channel) {
                    listener.onStatus("收到数据通道: " + channel.getLabel());
                    dataChannel = channel;
                    
                    // 设置 DataChannel 监听器
                    channel.registerObserver(new RTCDataChannelObserver() {
                        @Override
                        public void onStateChange() {
                            RTCDataChannelState state = channel.getState();
                            listener.onStatus("DataChannel 状态: " + state);
                            
                            // 通知聊天监听器状态变化
                            if (chatListener != null) {
                                chatListener.onDataChannelStateChange(state == RTCDataChannelState.OPEN);
                            }
                        }
                        
                        @Override
                        public void onMessage(RTCDataChannelBuffer buffer) {
                            try {
                                byte[] data = new byte[buffer.data.remaining()];
                                buffer.data.get(data);
                                String message = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                                
                                // 解析 JSON 消息
                                JsonNode root = mapper.readTree(message);
                                String kind = root.path("kind").asText("");
                                
                                if ("mouse".equals(kind)) {
                                    controlHandler.handleMouse(root);
                                } else if ("keyboard".equals(kind)) {
                                    controlHandler.handleKeyboard(root);
                                } else if ("chat".equals(kind)) {
                                    String sender = root.path("sender").asText("对方");
                                    String text = root.path("text").asText("");
                                    listener.onStatus("收到消息: " + sender + ": " + text);
                                    
                                    // 通知聊天监听器
                                    if (chatListener != null) {
                                        chatListener.onChatMessage(sender, text);
                                    }
                                }
                            } catch (Exception e) {
                                listener.onStatus("处理 DataChannel 消息失败: " + e.getMessage());
                            }
                        }
                        
                        @Override
                        public void onBufferedAmountChange(long previousAmount) {
                            // 可选：监控缓冲区变化
                        }
                    });
                }

                @Override
                public void onRenegotiationNeeded() {
                    listener.onStatus("需要重新协商");
                }

                @Override
                public void onAddStream(MediaStream stream) {
                    listener.onStatus("收到远端流");
                }

                @Override
                public void onRemoveStream(MediaStream stream) {
                    listener.onStatus("远端流移除");
                }
            });
            
            listener.onStatus("WebRTC 初始化完成");
        } catch (Exception e) {
            listener.onStatus("WebRTC 初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void handleOffer(JsonNode sdpNode) {
        try {
            listener.onStatus("处理 offer...");
            
            // 在处理 offer 之前先准备视频轨道
            if (screenSource == null) {
                startScreenCapture();
            }
            
            String sdpStr = sdpNode.path("sdp").asText();
            String type = sdpNode.path("type").asText();
            
            RTCSessionDescription offer = new RTCSessionDescription(
                    RTCSdpType.OFFER, sdpStr);
            
            peerConnection.setRemoteDescription(offer, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    listener.onStatus("设置远端描述成功，创建 answer...");
                    createAnswer();
                }

                @Override
                public void onFailure(String error) {
                    listener.onStatus("设置远端描述失败: " + error);
                }
            });
        } catch (Exception e) {
            listener.onStatus("处理 offer 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createAnswer() {
        try {
            // 视频轨道已在 handleOffer 中添加
            
            RTCAnswerOptions options = new RTCAnswerOptions();
            peerConnection.createAnswer(options, new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription answer) {
                    listener.onStatus("创建 answer 成功");
                    peerConnection.setLocalDescription(answer, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            listener.onStatus("设置本地描述成功，发送 answer");
                            try {
                                ObjectNode sdpJson = mapper.createObjectNode();
                                sdpJson.put("type", answer.sdpType.toString().toLowerCase());
                                sdpJson.put("sdp", answer.sdp);
                                
                                ObjectNode data = mapper.createObjectNode();
                                data.set("sdp", sdpJson);
                                
                                client.sendSignal("answer", data);
                            } catch (Exception e) {
                                listener.onStatus("发送 answer 失败: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onFailure(String error) {
                            listener.onStatus("设置本地描述失败: " + error);
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    listener.onStatus("创建 answer 失败: " + error);
                }
            });
        } catch (Exception e) {
            listener.onStatus("创建 answer 异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void handleAnswer(JsonNode sdpNode) {
        try {
            listener.onStatus("处理 answer...");
            
            String sdpStr = sdpNode.path("sdp").asText();
            
            RTCSessionDescription answer = new RTCSessionDescription(
                    RTCSdpType.ANSWER, sdpStr);
            
            peerConnection.setRemoteDescription(answer, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    listener.onStatus("设置远端描述成功");
                }

                @Override
                public void onFailure(String error) {
                    listener.onStatus("设置远端描述失败: " + error);
                }
            });
        } catch (Exception e) {
            listener.onStatus("处理 answer 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void handleIceCandidate(JsonNode candidateNode) {
        try {
            String candidate = candidateNode.path("candidate").asText();
            String sdpMid = candidateNode.path("sdpMid").asText();
            int sdpMLineIndex = candidateNode.path("sdpMLineIndex").asInt();
            
            RTCIceCandidate iceCandidate = new RTCIceCandidate(sdpMid, sdpMLineIndex, candidate);
            peerConnection.addIceCandidate(iceCandidate);
            
            listener.onStatus("添加 ICE 候选成功");
        } catch (Exception e) {
            listener.onStatus("添加 ICE 候选失败: " + e.getMessage());
        }
    }

    public void handleStreamConfig(JsonNode configNode) {
        try {
            int frameRate = configNode.path("frameRate").asInt(15);
            String bitrateMode = configNode.path("bitrateMode").asText("auto");
            int targetBitrate = configNode.path("targetBitrate").asInt(2000000);
            
            listener.onStatus("收到流配置: " + frameRate + "fps, " + bitrateMode + 
                            ", " + (targetBitrate/1000) + "kbps");
            
            // 更新帧率
            if (screenSource != null) {
                screenSource.updateFrameRate(frameRate);
            }
            
            // 更新码率
            if (videoSender != null && targetBitrate != currentTargetBitrate) {
                applyBitrate(targetBitrate);
            }
            
            listener.onStatus("流配置已应用");
        } catch (Exception e) {
            listener.onStatus("应用流配置失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void applyBitrate(int targetBitrate) {
        try {
            RTCRtpSendParameters params = videoSender.getParameters();
            
            if (params != null && params.encodings != null && !params.encodings.isEmpty()) {
                for (RTCRtpEncodingParameters encoding : params.encodings) {
                    encoding.maxBitrate = targetBitrate;
                    encoding.minBitrate = Math.max(100000, targetBitrate / 4);
                }
                
                videoSender.setParameters(params);
                currentTargetBitrate = targetBitrate;
                listener.onStatus("码率已更新: " + (targetBitrate/1000) + "kbps");
            } else {
                listener.onStatus("无法获取RTP参数，码率更新失败");
            }
        } catch (Exception e) {
            listener.onStatus("设置码率失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startScreenCapture() {
        try {
            listener.onStatus("启动屏幕捕获...");
            
            // 创建屏幕捕获源
            screenSource = new ScreenCaptureSource();
            listener.onStatus("屏幕捕获源已创建");
            
            // 创建视频轨道
            VideoTrack videoTrack = factory.createVideoTrack("screen-video", screenSource.getVideoSource());
            listener.onStatus("视频轨道已创建: screen-video");
            
            // 添加到 PeerConnection
            this.videoSender = peerConnection.addTrack(videoTrack, Collections.singletonList("stream-id"));
            listener.onStatus("视频轨道已添加到 PeerConnection, sender: " + videoSender);
            
            // 开始捕获
            screenSource.start();
            
            listener.onStatus("屏幕捕获已启动");
        } catch (Exception e) {
            listener.onStatus("启动屏幕捕获失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 发送聊天消息
     */
    public void sendChatMessage(String senderName, String text) {
        if (dataChannel != null && dataChannel.getState() == RTCDataChannelState.OPEN) {
            try {
                ObjectNode msg = mapper.createObjectNode();
                msg.put("kind", "chat");
                msg.put("sender", senderName != null && !senderName.isEmpty() ? senderName : "Agent");
                msg.put("text", text);
                
                String json = mapper.writeValueAsString(msg);
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
                dataChannel.send(new RTCDataChannelBuffer(buffer, false));
                
                listener.onStatus("已发送消息: " + text);
            } catch (Exception e) {
                listener.onStatus("发送聊天消息失败: " + e.getMessage());
            }
        } else {
            listener.onStatus("DataChannel 未就绪，无法发送消息");
        }
    }
    
    /**
     * 只清理RTC连接，保留Factory以便快速重建（用于peer-left场景）
     */
    public void cleanupRtcOnly() {
        listener.onStatus("清理RTC连接，保持就绪状态...");
        
        // 通知聊天禁用
        if (chatListener != null) {
            chatListener.onDataChannelStateChange(false);
        }
        
        // 1. 关闭 DataChannel
        if (dataChannel != null) {
            try {
                dataChannel.close();
            } catch (Exception e) {
                listener.onStatus("关闭 DataChannel 失败: " + e.getMessage());
            } finally {
                dataChannel = null;
            }
        }
        
        // 2. 停止屏幕捕获
        if (screenSource != null) {
            try {
                screenSource.stop();
            } catch (Exception e) {
                listener.onStatus("停止屏幕捕获失败: " + e.getMessage());
            } finally {
                screenSource = null;
            }
        }
        
        // 清理 videoSender 引用
        videoSender = null;
        
        // 3. 关闭 PeerConnection
        if (peerConnection != null) {
            try {
                peerConnection.close();
            } catch (Exception e) {
                listener.onStatus("关闭 PeerConnection 失败: " + e.getMessage());
            } finally {
                peerConnection = null;
            }
        }
        
        // 4. 重新创建 PeerConnection 准备下一次连接
        reinitPeerConnection();
        
        listener.onStatus("已就绪，等待新连接...");
    }
    
    /**
     * 重新初始化 PeerConnection（保留Factory）
     */
    private void reinitPeerConnection() {
        try {
            if (factory == null) {
                listener.onStatus("Factory已释放，需要完全重新初始化");
                init();
                return;
            }
            
            // 配置 ICE 服务器
            List<RTCIceServer> iceServers = new ArrayList<>();
            
            if (stunUrls != null) {
                for (String url : stunUrls) {
                    if (url != null && !url.trim().isEmpty()) {
                        RTCIceServer stunServer = new RTCIceServer();
                        stunServer.urls.add(url.trim());
                        iceServers.add(stunServer);
                    }
                }
            }
            
            if (turnUrls != null) {
                for (String url : turnUrls) {
                    if (url != null && !url.trim().isEmpty()) {
                        RTCIceServer turnServer = new RTCIceServer();
                        turnServer.urls.add(url.trim());
                        turnServer.username = turnUser;
                        turnServer.password = turnPass;
                        iceServers.add(turnServer);
                    }
                }
            }
            
            RTCConfiguration config = new RTCConfiguration();
            config.iceServers = iceServers;
            config.iceTransportPolicy = RTCIceTransportPolicy.ALL;
            config.bundlePolicy = RTCBundlePolicy.BALANCED;
            config.rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE;
            
            // 创建新的 PeerConnection
            peerConnection = factory.createPeerConnection(config, createPeerConnectionObserver());
            
            listener.onStatus("PeerConnection 已重建");
        } catch (Exception e) {
            listener.onStatus("重建 PeerConnection 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 创建 PeerConnection 观察者
     */
    private PeerConnectionObserver createPeerConnectionObserver() {
        return new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                listener.onStatus("ICE 候选: " + candidate.sdp);
                try {
                    ObjectNode candidateJson = mapper.createObjectNode();
                    candidateJson.put("candidate", candidate.sdp);
                    candidateJson.put("sdpMid", candidate.sdpMid);
                    candidateJson.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    
                    ObjectNode data = mapper.createObjectNode();
                    data.set("candidate", candidateJson);
                    
                    client.sendSignal("candidate", data);
                } catch (Exception e) {
                    listener.onStatus("发送 ICE 候选失败: " + e.getMessage());
                }
            }

            @Override
            public void onIceGatheringChange(RTCIceGatheringState state) {
                listener.onStatus("ICE 收集状态: " + state);
            }

            @Override
            public void onIceConnectionChange(RTCIceConnectionState state) {
                listener.onStatus("ICE 连接状态: " + state);
                
                if (state == RTCIceConnectionState.FAILED) {
                    listener.onStatus("ICE连接失败，尝试重启...");
                    try {
                        if (peerConnection != null) {
                            peerConnection.restartIce();
                            listener.onStatus("ICE重启已触发");
                        }
                    } catch (Exception e) {
                        listener.onStatus("ICE重启失败: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                listener.onStatus("连接状态: " + state);
            }

            @Override
            public void onSignalingChange(RTCSignalingState state) {
                listener.onStatus("信令状态: " + state);
            }

            @Override
            public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
                listener.onStatus("收到远端轨道");
            }

            @Override
            public void onRemoveTrack(RTCRtpReceiver receiver) {
                listener.onStatus("远端轨道移除");
            }

            @Override
            public void onDataChannel(RTCDataChannel channel) {
                listener.onStatus("收到数据通道: " + channel.getLabel());
                dataChannel = channel;
                
                channel.registerObserver(new RTCDataChannelObserver() {
                    @Override
                    public void onStateChange() {
                        RTCDataChannelState state = channel.getState();
                        listener.onStatus("DataChannel 状态: " + state);
                        
                        if (chatListener != null) {
                            chatListener.onDataChannelStateChange(state == RTCDataChannelState.OPEN);
                        }
                    }
                    
                    @Override
                    public void onMessage(RTCDataChannelBuffer buffer) {
                        try {
                            byte[] data = new byte[buffer.data.remaining()];
                            buffer.data.get(data);
                            String message = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                            
                            JsonNode root = mapper.readTree(message);
                            String kind = root.path("kind").asText("");
                            
                            if ("mouse".equals(kind)) {
                                controlHandler.handleMouse(root);
                            } else if ("keyboard".equals(kind)) {
                                controlHandler.handleKeyboard(root);
                            } else if ("chat".equals(kind)) {
                                String sender = root.path("sender").asText("对方");
                                String text = root.path("text").asText("");
                                listener.onStatus("收到消息: " + sender + ": " + text);
                                
                                if (chatListener != null) {
                                    chatListener.onChatMessage(sender, text);
                                }
                            }
                        } catch (Exception e) {
                            listener.onStatus("处理 DataChannel 消息失败: " + e.getMessage());
                        }
                    }
                    
                    @Override
                    public void onBufferedAmountChange(long previousAmount) {
                    }
                });
            }

            @Override
            public void onRenegotiationNeeded() {
                listener.onStatus("需要重新协商");
            }

            @Override
            public void onAddStream(MediaStream stream) {
                listener.onStatus("收到远端流");
            }

            @Override
            public void onRemoveStream(MediaStream stream) {
                listener.onStatus("远端流移除");
            }
        };
    }
    
    public void cleanup() {
        listener.onStatus("开始清理 WebRTC 资源...");
        
        // 1. 关闭 DataChannel
        if (dataChannel != null) {
            try {
                listener.onStatus("关闭 DataChannel...");
                dataChannel.close();
                listener.onStatus("DataChannel 已关闭");
            } catch (Exception e) {
                listener.onStatus("关闭 DataChannel 失败: " + e.getMessage());
            } finally {
                dataChannel = null;
            }
        }
        
        // 2. 停止屏幕捕获
        if (screenSource != null) {
            try {
                listener.onStatus("停止屏幕捕获...");
                screenSource.stop();
                listener.onStatus("屏幕捕获已停止");
            } catch (Exception e) {
                listener.onStatus("停止屏幕捕获失败: " + e.getMessage());
            } finally {
                screenSource = null;
            }
        }
        
        // 清理 videoSender 引用
        videoSender = null;
        
        // 3. 关闭 PeerConnection
        if (peerConnection != null) {
            try {
                listener.onStatus("关闭 PeerConnection...");
                peerConnection.close();
                listener.onStatus("PeerConnection 已关闭");
            } catch (Exception e) {
                listener.onStatus("关闭 PeerConnection 失败: " + e.getMessage());
            } finally {
                peerConnection = null;
            }
        }
        
        // 4. 释放 PeerConnectionFactory
        if (factory != null) {
            try {
                listener.onStatus("释放 PeerConnectionFactory...");
                factory.dispose();
                listener.onStatus("PeerConnectionFactory 已释放");
            } catch (Exception e) {
                listener.onStatus("释放 PeerConnectionFactory 失败: " + e.getMessage());
            } finally {
                factory = null;
            }
        }
        
        listener.onStatus("WebRTC 资源清理完成");
    }
}

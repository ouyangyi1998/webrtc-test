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

class WebRTCManager {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentClient client;
    private final AgentClient.StatusListener listener;
    private final String[] stunUrls;
    private final String[] turnUrls;
    private final String turnUser;
    private final String turnPass;
    private final ControlHandler controlHandler;

    private PeerConnectionFactory factory;
    private RTCPeerConnection peerConnection;
    private ScreenCaptureSource screenSource;
    private RTCDataChannel dataChannel;

    WebRTCManager(AgentClient client, AgentClient.StatusListener listener,
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

    void init() {
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
                            listener.onStatus("DataChannel 状态: " + channel.getState());
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
                                    listener.onStatus("Chat: " + root.path("text").asText(""));
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

    void handleOffer(JsonNode sdpNode) {
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

    void handleAnswer(JsonNode sdpNode) {
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

    void handleIceCandidate(JsonNode candidateNode) {
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
            RTCRtpSender sender = peerConnection.addTrack(videoTrack, Collections.singletonList("stream-id"));
            listener.onStatus("视频轨道已添加到 PeerConnection, sender: " + sender);
            
            // 开始捕获
            screenSource.start();
            
            listener.onStatus("屏幕捕获已启动");
        } catch (Exception e) {
            listener.onStatus("启动屏幕捕获失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    void cleanup() {
        try {
            if (dataChannel != null) {
                dataChannel.close();
                dataChannel = null;
            }
            if (screenSource != null) {
                screenSource.stop();
                screenSource = null;
            }
            if (peerConnection != null) {
                peerConnection.close();
                peerConnection = null;
            }
            if (factory != null) {
                factory.dispose();
                factory = null;
            }
            listener.onStatus("WebRTC 已清理");
        } catch (Exception e) {
            listener.onStatus("清理 WebRTC 失败: " + e.getMessage());
        }
    }
}

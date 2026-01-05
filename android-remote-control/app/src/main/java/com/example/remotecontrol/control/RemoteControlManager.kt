package com.example.remotecontrol.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.remotecontrol.signaling.SignalMessage
import com.example.remotecontrol.signaling.SignalingClient
import com.example.remotecontrol.webrtc.WebRTCManager
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * 远程控制管理器
 * 协调信令和 WebRTC 层
 */
class RemoteControlManager(
    private val context: Context,
    private val serverUrl: String,
    private val roomId: String,
    private val nickname: String,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "RemoteControlManager"
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        VIDEO_READY
    }

    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onVideoTrackReceived(track: VideoTrack)
        fun onDataChannelReady()
        fun onChatMessage(sender: String, text: String)
        fun onError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var signalingClient: SignalingClient? = null
    private var webRTCManager: WebRTCManager? = null

    private var isInitiator = false
    private var isJoined = false
    private var connectionState = ConnectionState.DISCONNECTED

    /**
     * 初始化并连接
     */
    fun connect(screenCaptureIntent: android.content.Intent?) {
        if (connectionState != ConnectionState.DISCONNECTED) {
            Log.w(TAG, "Already connecting or connected")
            return
        }

        updateConnectionState(ConnectionState.CONNECTING)

        // 初始化 WebRTC
        webRTCManager = WebRTCManager(context, webRTCListener)
        webRTCManager?.initialize()
        
        // 创建并添加本地视频轨道（屏幕共享）
        if (screenCaptureIntent != null) {
            val videoTrack = webRTCManager?.createScreenCaptureVideoTrack(screenCaptureIntent)
            if (videoTrack != null) {
                // 将 Track 添加到 PeerConnection 需要在 createPeerConnection 之后
                // 但这里我们只保存引用，由 WebRTCManager 在创建 PC 时添加
                // 修正：WebRTCManager 需要调整以支持添加本地流
                // 暂时方案：修改 WebRTCManager 逻辑，使其能持有本地 Track
                Log.d(TAG, "Screen capture video track created")
            }
        }

        // 连接信令服务器
        signalingClient = SignalingClient(serverUrl, signalingListener)
        signalingClient?.connect()
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        signalingClient?.leaveRoom(roomId, nickname)
        signalingClient?.disconnect()
        signalingClient = null

        webRTCManager?.release()
        webRTCManager = null

        isJoined = false
        isInitiator = false
        updateConnectionState(ConnectionState.DISCONNECTED)
    }

    /**
     * 发送鼠标移动
     */
    fun sendMouseMove(xRatio: Float, yRatio: Float) {
        sendControlMessage(ControlPayload.mouseMove(xRatio, yRatio))
    }

    /**
     * 发送鼠标按下
     */
    fun sendMouseDown(xRatio: Float, yRatio: Float, button: Int = 0) {
        sendControlMessage(ControlPayload.mouseDown(xRatio, yRatio, button))
    }

    /**
     * 发送鼠标释放
     */
    fun sendMouseUp(xRatio: Float, yRatio: Float, button: Int = 0) {
        sendControlMessage(ControlPayload.mouseUp(xRatio, yRatio, button))
    }

    /**
     * 发送鼠标双击
     */
    fun sendMouseDoubleClick(xRatio: Float, yRatio: Float, button: Int = 0) {
        sendControlMessage(ControlPayload.mouseDoubleClick(xRatio, yRatio, button))
    }

    /**
     * 发送鼠标滚轮
     */
    fun sendMouseWheel(xRatio: Float, yRatio: Float, deltaY: Float) {
        sendControlMessage(ControlPayload.mouseWheel(xRatio, yRatio, deltaY))
    }

    /**
     * 发送键盘按下
     */
    fun sendKeyDown(key: String, code: String, modifiers: Map<String, Boolean> = emptyMap()) {
        sendControlMessage(ControlPayload.keyboard("keydown", key, code,
            modifiers["alt"] ?: false,
            modifiers["ctrl"] ?: false,
            modifiers["meta"] ?: false,
            modifiers["shift"] ?: false
        ))
    }

    /**
     * 发送键盘释放
     */
    fun sendKeyUp(key: String, code: String, modifiers: Map<String, Boolean> = emptyMap()) {
        sendControlMessage(ControlPayload.keyboard("keyup", key, code,
            modifiers["alt"] ?: false,
            modifiers["ctrl"] ?: false,
            modifiers["meta"] ?: false,
            modifiers["shift"] ?: false
        ))
    }

    /**
     * 发送聊天消息
     */
    fun sendChatMessage(text: String) {
        sendControlMessage(ControlPayload.chat(nickname, text))
    }

    /**
     * 获取 EGL Context
     */
    fun getEglContext() = webRTCManager?.getEglContext()

    /**
     * 获取当前连接状态
     */
    fun getConnectionState() = connectionState

    private fun sendControlMessage(message: String) {
        // 优先使用 DataChannel
        val sent = webRTCManager?.sendDataChannelMessage(message) ?: false
        if (!sent) {
            // DataChannel 不可用时，控制消息将被丢弃
            // 因为 WebSocket 降级发送控制消息需要完整解析 JSON，这里简化处理
            Log.w(TAG, "DataChannel not available, control message dropped")
        }
    }

    private fun updateConnectionState(state: ConnectionState) {
        connectionState = state
        mainHandler.post {
            listener.onConnectionStateChanged(state)
        }
    }

    // 信令监听器
    private val signalingListener = object : SignalingClient.Listener {
        override fun onConnected() {
            Log.d(TAG, "Signaling connected, joining room: $roomId")
            signalingClient?.joinRoom(roomId, nickname)
        }

        override fun onDisconnected() {
            Log.d(TAG, "Signaling disconnected")
            if (connectionState != ConnectionState.DISCONNECTED) {
                updateConnectionState(ConnectionState.CONNECTING)
            }
        }

        override fun onMessage(message: SignalMessage) {
            mainHandler.post {
                handleSignalingMessage(message)
            }
        }

        override fun onError(error: String) {
            Log.e(TAG, "Signaling error: $error")
            mainHandler.post {
                listener.onError(error)
            }
        }
    }

    private fun handleSignalingMessage(message: SignalMessage) {
        when (message.type) {
            "join-ack" -> {
                val participants = message.getParticipants()
                Log.d(TAG, "Joined room, participants: $participants")
                isJoined = true

                if (participants > 1) {
//                    // 房间已有成员，主动创建 offer
//                    isInitiator = true
//                    webRTCManager?.createPeerConnection()
//                    webRTCManager?.createOffer()
                }
            }

            "peer-joined" -> {
                Log.d(TAG, "Peer joined: ${message.sender}")
//                isInitiator = true
//                webRTCManager?.createPeerConnection()
//                webRTCManager?.createOffer()
            }

            "peer-left" -> {
                Log.d(TAG, "Peer left: ${message.sender}")
                webRTCManager?.closePeerConnection()
                updateConnectionState(ConnectionState.CONNECTED)
            }

            "offer" -> {
                Log.d(TAG, "Received offer from: ${message.sender}")
                val sdp = message.getSdp()
                if (sdp != null) {
                    // 确保 PeerConnection 已创建
                    webRTCManager?.createPeerConnection()
                    webRTCManager?.handleOffer(sdp.first, sdp.second)
                }
            }

            "answer" -> {
                Log.d(TAG, "Received answer from: ${message.sender}")
                val sdp = message.getSdp()
                if (sdp != null) {
                    webRTCManager?.handleAnswer(sdp.first, sdp.second)
                }
            }

            "candidate" -> {
                Log.d(TAG, "Received candidate from: ${message.sender}")
                val candidate = message.getCandidate()
                if (candidate != null) {
                    webRTCManager?.addIceCandidate(candidate.first, candidate.second, candidate.third)
                }
            }

            "pong" -> {
                // 心跳响应
            }

            "error" -> {
                val errorMsg = message.getErrorMessage()
                Log.e(TAG, "Server error: $errorMsg")
                listener.onError(errorMsg)
            }
        }
    }

    // WebRTC 监听器
    private val webRTCListener = object : WebRTCManager.Listener {
        override fun onLocalIceCandidate(candidate: IceCandidate) {
            signalingClient?.sendCandidate(
                roomId, nickname,
                candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex
            )
        }

        override fun onIceConnectionStateChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ICE state: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    updateConnectionState(ConnectionState.CONNECTED)
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    // 等待恢复
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    webRTCManager?.restartIce()
                }
                else -> {}
            }
        }

        override fun onDataChannelOpen() {
            Log.d(TAG, "DataChannel opened")
            mainHandler.post {
                listener.onDataChannelReady()
            }
        }

        override fun onDataChannelMessage(message: String) {
            // 处理接收到的消息（如聊天）
            try {
                val json = com.google.gson.JsonParser.parseString(message).asJsonObject
                val kind = json.get("kind")?.asString
                if (kind == "chat") {
                    val sender = json.get("sender")?.asString ?: "peer"
                    val text = json.get("text")?.asString ?: ""
                    mainHandler.post {
                        listener.onChatMessage(sender, text)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse DataChannel message: $e")
            }
        }

        override fun onDataChannelClose() {
            Log.d(TAG, "DataChannel closed")
        }

        override fun onVideoTrackReceived(track: VideoTrack) {
            Log.d(TAG, "Video track received")
            updateConnectionState(ConnectionState.VIDEO_READY)
            mainHandler.post {
                listener.onVideoTrackReceived(track)
            }
        }

        override fun onOfferCreated(sdp: SessionDescription) {
            signalingClient?.sendOffer(
                roomId, nickname,
                sdp.type.canonicalForm(), sdp.description
            )
        }

        override fun onAnswerCreated(sdp: SessionDescription) {
            signalingClient?.sendAnswer(
                roomId, nickname,
                sdp.type.canonicalForm(), sdp.description
            )
        }

        override fun onError(error: String) {
            Log.e(TAG, "WebRTC error: $error")
            mainHandler.post {
                listener.onError(error)
            }
        }
    }
}

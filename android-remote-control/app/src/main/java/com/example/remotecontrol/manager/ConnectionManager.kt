package com.example.remotecontrol.manager

import android.content.Context
import com.example.remotecontrol.control.RemoteControlManager
import com.example.remotecontrol.signaling.SignalingClient
import com.example.remotecontrol.webrtc.WebRTCManager
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

/**
 * 连接管理器（全局单例）
 * 协调 SignalingClient 和 WebRTCManager
 */
object ConnectionManager {
    private var signalingClient: SignalingClient? = null
    private var webRTCManager: WebRTCManager? = null
    private var configManager: ConfigManager? = null
    
    private val stateListeners = mutableListOf<ConnectionStateListener>()
    private val chatListeners = mutableListOf<ChatListener>()
    
    enum class State {
        DISCONNECTED, CONNECTING, SIGNALING_CONNECTED, PEER_CONNECTED, DATA_CHANNEL_OPEN
    }
    
    var currentState = State.DISCONNECTED
        private set
    
    data class StatusInfo(
        val wsStatus: String = "未连接",
        val dcStatus: String = "未连接",
        val iceStatus: String = "未初始化",
        val overallStatus: String = "空闲",
        val roomId: String = "",
        val nickname: String = ""
    )
    
    var statusInfo = StatusInfo()
        private set
    
    interface ConnectionStateListener {
        fun onStateChanged(state: State, info: StatusInfo)
        fun onVideoTrackReceived(track: VideoTrack)
        fun onError(error: String)
    }
    
    interface ChatListener {
        fun onChatMessage(sender: String, message: String)
        fun onDataChannelStateChanged(isOpen: Boolean)
    }
    
    interface ControlListener {
        fun onMouseControl(action: String, xRatio: Float, yRatio: Float, button: Int, deltaY: Float)
        fun onKeyboardControl(type: String, key: String, code: String, 
                              altKey: Boolean, ctrlKey: Boolean, metaKey: Boolean, shiftKey: Boolean)
    }
    
    private val controlListeners = mutableListOf<ControlListener>()
    
    fun addStateListener(listener: ConnectionStateListener) {
        stateListeners.add(listener)
    }
    
    fun removeStateListener(listener: ConnectionStateListener) {
        stateListeners.remove(listener)
    }
    
    fun addChatListener(listener: ChatListener) {
        chatListeners.add(listener)
    }
    
    fun removeChatListener(listener: ChatListener) {
        chatListeners.remove(listener)
    }
    
    fun addControlListener(listener: ControlListener) {
        controlListeners.add(listener)
    }
    
    fun removeControlListener(listener: ControlListener) {
        controlListeners.remove(listener)
    }
    
    fun connect(context: Context, config: ConfigManager) {
        if (currentState != State.DISCONNECTED) {
            LogManager.w("已经在连接中或已连接")
            return
        }
        
        configManager = config
        updateState(State.CONNECTING)
        updateStatus(wsStatus = "连接中...", overallStatus = "连接中...", 
                    roomId = config.roomId, nickname = config.nickname)
        
        LogManager.i("开始连接: ${config.signalUrl}, 房间: ${config.roomId}")
        
        // 初始化 WebRTC
        webRTCManager = WebRTCManager(context, webRTCListener)
        webRTCManager?.initialize()
        
        // 连接信令服务器
        signalingClient = SignalingClient(config.signalUrl, signalingListener)
        signalingClient?.connect()
    }
    
    fun disconnect() {
        LogManager.i("断开连接")
        
        signalingClient?.disconnect()
        signalingClient = null
        
        webRTCManager?.release()
        webRTCManager = null
        
        updateState(State.DISCONNECTED)
        updateStatus(wsStatus = "已断开", dcStatus = "未连接", 
                    iceStatus = "未初始化", overallStatus = "已停止")
    }
    
    fun sendChatMessage(message: String) {
        val nickname = configManager?.nickname ?: "Android"
        val payload = com.example.remotecontrol.control.ControlPayload.chat(nickname, message)
        webRTCManager?.sendDataChannelMessage(payload)
        LogManager.i("发送聊天: $message")
    }
    
    fun getEglContext() = webRTCManager?.getEglContext()
    
    fun isConnected() = currentState == State.DATA_CHANNEL_OPEN || currentState == State.PEER_CONNECTED
    
    private fun updateState(state: State) {
        currentState = state
        stateListeners.forEach { it.onStateChanged(state, statusInfo) }
    }
    
    private fun updateStatus(
        wsStatus: String? = null,
        dcStatus: String? = null,
        iceStatus: String? = null,
        overallStatus: String? = null,
        roomId: String? = null,
        nickname: String? = null
    ) {
        statusInfo = statusInfo.copy(
            wsStatus = wsStatus ?: statusInfo.wsStatus,
            dcStatus = dcStatus ?: statusInfo.dcStatus,
            iceStatus = iceStatus ?: statusInfo.iceStatus,
            overallStatus = overallStatus ?: statusInfo.overallStatus,
            roomId = roomId ?: statusInfo.roomId,
            nickname = nickname ?: statusInfo.nickname
        )
        stateListeners.forEach { it.onStateChanged(currentState, statusInfo) }
    }
    
    // ========== 信令监听器 ==========
    private val signalingListener = object : SignalingClient.Listener {
        override fun onConnected() {
            LogManager.i("WebSocket 已连接")
            updateStatus(wsStatus = "已连接", overallStatus = "等待加入房间...")
            
            val config = configManager ?: return
            signalingClient?.joinRoom(config.roomId, config.nickname)
        }
        
        override fun onDisconnected() {
            LogManager.w("WebSocket 已断开")
            updateStatus(wsStatus = "已断开", overallStatus = "连接断开")
            if (currentState != State.DISCONNECTED) {
                updateState(State.CONNECTING)
            }
        }
        
        override fun onMessage(message: com.example.remotecontrol.signaling.SignalMessage) {
            handleSignalingMessage(message)
        }
        
        override fun onError(error: String) {
            LogManager.e("信令错误: $error")
            stateListeners.forEach { it.onError(error) }
        }
    }
    
    private fun handleSignalingMessage(message: com.example.remotecontrol.signaling.SignalMessage) {
        when (message.type) {
            "join-ack" -> {
                val participants = message.getParticipants()
                LogManager.i("加入房间成功，在线人数: $participants")
                updateState(State.SIGNALING_CONNECTED)
                updateStatus(overallStatus = "等待控制端连接...")
                
                if (participants > 1) {
                    val config = configManager ?: return
                    LogManager.i("房间已有成员，主动发起连接")
                    val iceServers = config.getIceServers().map { 
                        WebRTCManager.IceServerConfig(it.url, it.username, it.password)
                    }
                    webRTCManager?.createPeerConnection(iceServers)
                    webRTCManager?.createOffer()
                }
            }
            
            "peer-joined" -> {
                LogManager.i("对端加入: ${message.sender}")
                // 创建 PeerConnection 并等待 offer
                val config = configManager ?: return
                val iceServers = config.getIceServers().map { 
                    WebRTCManager.IceServerConfig(it.url, it.username, it.password)
                }
                webRTCManager?.createPeerConnection(iceServers)
                LogManager.i("主动发起连接 (Offer)")
                webRTCManager?.createOffer()
            }
            
            "peer-left" -> {
                LogManager.i("对端离开: ${message.sender}")
                webRTCManager?.closePeerConnection()
                updateStatus(dcStatus = "未连接", iceStatus = "对端已离开", overallStatus = "等待控制端...")
            }
            
            "offer" -> {
                LogManager.i("收到 offer")
                val sdp = message.getSdp()
                if (sdp != null) {
                    val config = configManager ?: return
                    val iceServers = config.getIceServers().map {
                        WebRTCManager.IceServerConfig(it.url, it.username, it.password)
                    }
                    webRTCManager?.createPeerConnection(iceServers)
                    webRTCManager?.handleOffer(sdp.first, sdp.second)
                }
            }
            
            "answer" -> {
                LogManager.i("收到 answer")
                val sdp = message.getSdp()
                if (sdp != null) {
                    webRTCManager?.handleAnswer(sdp.first, sdp.second)
                }
            }
            
            "candidate" -> {
                val candidate = message.getCandidate()
                if (candidate != null) {
                    webRTCManager?.addIceCandidate(candidate.first, candidate.second, candidate.third)
                }
            }
            
            "error" -> {
                val errorMsg = message.getErrorMessage()
                LogManager.e("服务器错误: $errorMsg")
                stateListeners.forEach { it.onError(errorMsg) }
            }
        }
    }
    
    // ========== WebRTC 监听器 ==========
    private val webRTCListener = object : WebRTCManager.Listener {
        override fun onLocalIceCandidate(candidate: org.webrtc.IceCandidate) {
            val config = configManager ?: return
            signalingClient?.sendCandidate(
                config.roomId, config.nickname,
                candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex
            )
        }
        
        override fun onIceConnectionStateChange(state: PeerConnection.IceConnectionState) {
            LogManager.i("ICE 状态: $state")
            updateStatus(iceStatus = state.name)
            
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    updateState(State.PEER_CONNECTED)
                    updateStatus(overallStatus = "已连接")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    updateStatus(overallStatus = "连接失败")
                    webRTCManager?.restartIce()
                }
                else -> {}
            }
        }
        
        override fun onDataChannelOpen() {
            LogManager.i("DataChannel 已打开")
            updateState(State.DATA_CHANNEL_OPEN)
            updateStatus(dcStatus = "已连接", overallStatus = "控制就绪")
            chatListeners.forEach { it.onDataChannelStateChanged(true) }
        }
        
        override fun onDataChannelMessage(message: String) {
            try {
                val json = com.google.gson.JsonParser.parseString(message).asJsonObject
                val kind = json.get("kind")?.asString
                when (kind) {
                    "chat" -> {
                        val sender = json.get("sender")?.asString ?: "peer"
                        val text = json.get("text")?.asString ?: ""
                        chatListeners.forEach { it.onChatMessage(sender, text) }
                    }
                    "mouse" -> {
                        val action = json.get("action")?.asString ?: ""
                        val xRatio = json.get("xRatio")?.asFloat ?: 0f
                        val yRatio = json.get("yRatio")?.asFloat ?: 0f
                        val button = json.get("button")?.asInt ?: 0
                        val deltaY = json.get("deltaY")?.asFloat ?: 0f
                        controlListeners.forEach { 
                            it.onMouseControl(action, xRatio, yRatio, button, deltaY) 
                        }
                        LogManager.d("收到鼠标控制: action=$action, x=$xRatio, y=$yRatio")
                    }
                    "keyboard" -> {
                        val type = json.get("type")?.asString ?: ""
                        val key = json.get("key")?.asString ?: ""
                        val code = json.get("code")?.asString ?: ""
                        val altKey = json.get("altKey")?.asBoolean ?: false
                        val ctrlKey = json.get("ctrlKey")?.asBoolean ?: false
                        val metaKey = json.get("metaKey")?.asBoolean ?: false
                        val shiftKey = json.get("shiftKey")?.asBoolean ?: false
                        controlListeners.forEach {
                            it.onKeyboardControl(type, key, code, altKey, ctrlKey, metaKey, shiftKey)
                        }
                        LogManager.d("收到键盘控制: type=$type, key=$key, code=$code")
                    }
                }
            } catch (e: Exception) {
                LogManager.e("解析 DataChannel 消息失败: ${e.message}")
            }
        }
        
        override fun onDataChannelClose() {
            LogManager.w("DataChannel 已关闭")
            updateStatus(dcStatus = "已关闭")
            chatListeners.forEach { it.onDataChannelStateChanged(false) }
        }
        
        override fun onVideoTrackReceived(track: VideoTrack) {
            LogManager.i("收到视频轨道")
            stateListeners.forEach { it.onVideoTrackReceived(track) }
        }
        
        override fun onOfferCreated(sdp: org.webrtc.SessionDescription) {
            val config = configManager ?: return
            signalingClient?.sendOffer(config.roomId, config.nickname, sdp.type.canonicalForm(), sdp.description)
        }
        
        override fun onAnswerCreated(sdp: org.webrtc.SessionDescription) {
            val config = configManager ?: return
            signalingClient?.sendAnswer(config.roomId, config.nickname, sdp.type.canonicalForm(), sdp.description)
        }
        
        override fun onError(error: String) {
            LogManager.e("WebRTC 错误: $error")
            stateListeners.forEach { it.onError(error) }
        }
    }
}

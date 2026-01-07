package com.example.remotecontrol.signaling

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * WebSocket 信令客户端
 * 负责与信令服务器通信
 */
class SignalingClient(
    private val serverUrl: String,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "SignalingClient"
        private const val HEARTBEAT_INTERVAL = 30000L  // 30秒
        private const val HEARTBEAT_TIMEOUT = 15000L   // 15秒超时
        private const val RECONNECT_BASE_DELAY = 1000L
        private const val RECONNECT_MAX_DELAY = 10000L
        private const val MAX_RECONNECT_ATTEMPTS = 20
    }

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(message: SignalMessage)
        fun onError(error: String)
    }

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var isConnecting = false
    private var isOpen = false  // 实际连接状态
    private var shouldReconnect = true
    private var pongReceived = true  // 心跳响应标志
    private var reconnectAttempts = 0

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // 无超时
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 连接到信令服务器
     */
    fun connect() {
        if (webSocket != null || isConnecting) {
            Log.w(TAG, "Already connected or connecting")
            return
        }

        isConnecting = true
        shouldReconnect = true

        val request = Request.Builder()
            .url(serverUrl)
            .build()
            
        Log.d(TAG, "Connecting to WebSocket URL: $serverUrl")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to $serverUrl")
                isConnecting = false
                isOpen = true
                startHeartbeat()
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                
                // 检测 pong 响应
                if (text.contains("\"type\":\"pong\"")) {
                    pongReceived = true
                    return
                }
                
                val message = SignalMessage.fromJson(text)
                if (message != null) {
                    reconnectAttempts = 0  // 收到有效消息，重置重连计数
                    listener.onMessage(message)
                } else {
                    Log.w(TAG, "Failed to parse message: $text")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                isConnecting = false
                listener.onError(t.message ?: "Connection failed")
                handleDisconnect()
            }
        })
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        shouldReconnect = false
        isOpen = false
        stopHeartbeat()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
    }

    /**
     * 发送消息
     */
    fun send(message: String): Boolean {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "WebSocket not connected")
            return false
        }
        Log.d(TAG, "Sending: $message")
        return ws.send(message)
    }

    /**
     * 加入房间
     */
    fun joinRoom(roomId: String, sender: String) {
        send(SignalMessage.join(roomId, sender))
    }

    /**
     * 离开房间
     */
    fun leaveRoom(roomId: String, sender: String) {
        send(SignalMessage.leave(roomId, sender))
    }

    /**
     * 发送 Offer
     */
    fun sendOffer(roomId: String, sender: String, sdpType: String, sdp: String) {
        send(SignalMessage.offer(roomId, sender, sdpType, sdp))
    }

    /**
     * 发送 Answer
     */
    fun sendAnswer(roomId: String, sender: String, sdpType: String, sdp: String) {
        send(SignalMessage.answer(roomId, sender, sdpType, sdp))
    }

    /**
     * 发送 ICE Candidate
     */
    fun sendCandidate(roomId: String, sender: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        send(SignalMessage.candidate(roomId, sender, candidate, sdpMid, sdpMLineIndex))
    }

    /**
     * 发送控制消息（降级通道）
     */
    fun sendControl(roomId: String, sender: String, payload: Map<String, Any>) {
        send(SignalMessage.control(roomId, sender, payload))
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        pongReceived = true
        heartbeatJob = scope.launch {
            while (isActive && isOpen) {
                delay(HEARTBEAT_INTERVAL)
                
                // 检查上一次心跳是否收到响应
                if (!pongReceived) {
                    Log.w(TAG, "心跳超时，连接可能已断开")
                    // 触发断开处理
                    webSocket?.close(1000, "Heartbeat timeout")
                    break
                }
                
                // 发送新的心跳
                pongReceived = false
                send(SignalMessage.ping())
                
                // 设置超时检测
                delay(HEARTBEAT_TIMEOUT)
                if (!pongReceived && isOpen) {
                    Log.w(TAG, "心跳响应超时 (${HEARTBEAT_TIMEOUT}ms)，强制断开")
                    webSocket?.close(1000, "Pong timeout")
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun handleDisconnect() {
        stopHeartbeat()
        isOpen = false
        webSocket = null
        listener.onDisconnected()

        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        
        // 检查重连次数
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "重连次数已达上限 ($MAX_RECONNECT_ATTEMPTS)，停止自动重连")
            listener.onError("连接失败，已达最大重连次数")
            return
        }
        
        reconnectJob = scope.launch {
            val delay = minOf(RECONNECT_BASE_DELAY * (1L shl reconnectAttempts), RECONNECT_MAX_DELAY)
            reconnectAttempts++
            Log.d(TAG, "Reconnecting in ${delay}ms... (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
            delay(delay)
            if (shouldReconnect && webSocket == null) {
                isConnecting = false  // 允许重新连接
                connect()
            }
        }
    }

    fun isConnected(): Boolean = webSocket != null && isOpen

    fun cleanup() {
        shouldReconnect = false
        scope.cancel()
        stopHeartbeat()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Cleanup")
        webSocket = null
        client.dispatcher.executorService.shutdown()
    }
}

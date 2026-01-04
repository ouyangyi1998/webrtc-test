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
        private const val RECONNECT_BASE_DELAY = 1000L
        private const val RECONNECT_MAX_DELAY = 10000L
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
    private var shouldReconnect = true

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

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to $serverUrl")
                isConnecting = false
                startHeartbeat()
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                val message = SignalMessage.fromJson(text)
                if (message != null) {
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
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                send(SignalMessage.ping())
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun handleDisconnect() {
        stopHeartbeat()
        webSocket = null
        listener.onDisconnected()

        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delay = RECONNECT_BASE_DELAY
            while (isActive && shouldReconnect && webSocket == null) {
                Log.d(TAG, "Reconnecting in ${delay}ms...")
                delay(delay)
                if (shouldReconnect) {
                    connect()
                }
                delay = minOf(delay * 2, RECONNECT_MAX_DELAY)
            }
        }
    }

    fun isConnected(): Boolean = webSocket != null

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

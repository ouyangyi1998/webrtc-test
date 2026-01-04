# Android 接入 WebRTC 远程控制手册

> **📢 注意**：
> - 本文档主要介绍 **Android 作为控制端**（去控制别的设备）的开发流程。
> - 如果你想开发 **Android 作为被控端**（Agent，让别人来控制你的手机），请移步至 👉 **[Android 被控端 (Agent) 极简开发手册](./ANDROID_AGENT_MANUAL.md)**。

---

## 📋 目录

1. [系统概述](#系统概述)
2. [环境准备](#环境准备)
3. [WebSocket 信令协议](#websocket-信令协议)
4. [WebRTC 配置](#webrtc-配置)
5. [连接流程](#连接流程)
6. [消息格式详解](#消息格式详解)
7. [数据通道协议](#数据通道协议)
8. [完整代码示例](#完整代码示例)
9. [注意事项](#注意事项)

---

## 系统概述

这是一个基于 WebRTC 的远程桌面控制系统，包含三个角色：

- **Web 端（控制端）**：显示远程屏幕，发送鼠标/键盘控制指令
- **Agent 端（被控端）**：捕获屏幕，接收并执行控制指令
- **信令服务器**：协调两端建立 WebRTC 连接

**Android 端作为控制端接入**，需要实现：
1. WebSocket 信令连接
2. WebRTC PeerConnection 建立
3. 接收远程视频流
4. 发送鼠标/键盘控制指令

---

## 环境准备

### 1. 添加依赖

在 `build.gradle` (Module: app) 中添加：

```gradle
dependencies {
    // WebRTC Android SDK
    implementation 'org.webrtc:google-webrtc:1.0.32006'
    
    // WebSocket 客户端
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    
    // JSON 解析
    implementation 'com.google.code.gson:gson:2.10.1'
    
    // 协程支持（可选，用于异步处理）
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
}
```

### 2. 权限配置

在 `AndroidManifest.xml` 中添加：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### 3. 初始化 WebRTC

```kotlin
import org.webrtc.*

class MainActivity : AppCompatActivity() {
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化 WebRTC
        val initializationOptions = PeerConnectionFactory.InitializationOptions
            .builder(applicationContext)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)
        
        // 创建 PeerConnectionFactory
        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(
            null, /* eglContext */
            false, /* enableIntelVp8Encoder */
            true   /* enableH264HighProfile */
        )
        val decoderFactory = DefaultVideoDecoderFactory(null)
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }
}
```

---

## WebSocket 信令协议

### 连接地址

```
ws://your-server-host:port/ws
或
wss://your-server-host:port/ws  (HTTPS)
```

### 消息格式

所有消息都是 JSON 格式，统一结构：

```json
{
  "type": "消息类型",
  "roomId": "房间ID",
  "sender": "发送者名称",
  "data": { /* 消息数据 */ }
}
```

### 消息类型

#### 1. 加入房间 (join)

**客户端发送：**
```json
{
  "type": "join",
  "roomId": "room123",
  "sender": "AndroidClient"
}
```

**服务器响应 (join-ack)：**
```json
{
  "type": "join-ack",
  "roomId": "room123",
  "sender": "server",
  "data": {
    "participants": 2  // 当前房间人数
  }
}
```

**说明：**
- 如果 `participants > 1`，说明房间已有成员，Android 端应该主动创建 offer
- 如果 `participants == 1`，等待 `peer-joined` 消息

#### 2. 对端加入 (peer-joined)

**服务器推送：**
```json
{
  "type": "peer-joined",
  "roomId": "room123",
  "sender": "AgentClient"
}
```

**说明：** 收到此消息后，Android 端应该创建 offer

#### 3. 对端离开 (peer-left)

**服务器推送：**
```json
{
  "type": "peer-left",
  "roomId": "room123",
  "sender": "AgentClient"
}
```

**说明：** 清理 WebRTC 连接，但保持 WebSocket 连接，等待对端重新加入

#### 4. Offer 信令

**Android 端发送：**
```json
{
  "type": "offer",
  "roomId": "room123",
  "sender": "AndroidClient",
  "data": {
    "sdp": {
      "type": "offer",
      "sdp": "v=0\r\no=- 1234567890..."
    }
  }
}
```

**Android 端接收：**
```json
{
  "type": "offer",
  "roomId": "room123",
  "sender": "AgentClient",
  "data": {
    "sdp": {
      "type": "offer",
      "sdp": "v=0\r\no=- 1234567890..."
    }
  }
}
```

#### 5. Answer 信令

**Android 端发送：**
```json
{
  "type": "answer",
  "roomId": "room123",
  "sender": "AndroidClient",
  "data": {
    "sdp": {
      "type": "answer",
      "sdp": "v=0\r\no=- 1234567890..."
    }
  }
}
```

**Android 端接收：**
```json
{
  "type": "answer",
  "roomId": "room123",
  "sender": "AgentClient",
  "data": {
    "sdp": {
      "type": "answer",
      "sdp": "v=0\r\no=- 1234567890..."
    }
  }
}
```

#### 6. ICE Candidate

**Android 端发送：**
```json
{
  "type": "candidate",
  "roomId": "room123",
  "sender": "AndroidClient",
  "data": {
    "candidate": {
      "candidate": "candidate:1 1 UDP 2130706431 192.168.1.100 54321 typ host",
      "sdpMid": "0",
      "sdpMLineIndex": 0
    }
  }
}
```

**Android 端接收：**
```json
{
  "type": "candidate",
  "roomId": "room123",
  "sender": "AgentClient",
  "data": {
    "candidate": {
      "candidate": "candidate:1 1 UDP 2130706431 192.168.1.100 54321 typ host",
      "sdpMid": "0",
      "sdpMLineIndex": 0
    }
  }
}
```

#### 7. 心跳 (ping/pong)

**Android 端发送（每30秒）：**
```json
{
  "type": "ping"
}
```

**服务器响应：**
```json
{
  "type": "pong"
}
```

#### 8. 离开房间 (leave)

**Android 端发送：**
```json
{
  "type": "leave",
  "roomId": "room123",
  "sender": "AndroidClient"
}
```

#### 9. 错误消息 (error)

**服务器推送：**
```json
{
  "type": "error",
  "roomId": "room123",
  "sender": "server",
  "data": {
    "message": "错误描述"
  }
}
```

---

## WebRTC 配置

### ICE 服务器配置

```kotlin
fun createIceServers(): List<PeerConnection.IceServer> {
    val servers = mutableListOf<PeerConnection.IceServer>()
    
    // STUN 服务器（示例）
    servers.add(
        PeerConnection.IceServer.builder("stun:stun.example.com:3478")
            .createIceServer()
    )
    
    // TURN 服务器（示例）
    servers.add(
        PeerConnection.IceServer.builder("turn:turn.example.com:3478")
            .setUsername("username")
            .setPassword("password")
            .createIceServer()
    )
    
    return servers
}
```

### PeerConnection 配置

```kotlin
fun createPeerConnection(): PeerConnection {
    val rtcConfig = PeerConnection.RTCConfiguration(createIceServers())
    rtcConfig.iceTransportPolicy = PeerConnection.IceTransportPolicy.ALL
    rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.BALANCED
    rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
    rtcConfig.iceCandidatePoolSize = 0  // 设置为 0 以减少初始收集时间
    
    return peerConnectionFactory.createPeerConnection(
        rtcConfig,
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }
            
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        // 等待 5 秒恢复，否则重启 ICE
                        handler.postDelayed({
                            if (peerConnection?.iceConnectionState() == 
                                PeerConnection.IceConnectionState.DISCONNECTED) {
                                peerConnection?.restartIce()
                            }
                        }, 5000)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        // ICE 失败，重启
                        peerConnection?.restartIce()
                    }
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        // 连接成功
                    }
                    else -> {}
                }
            }
            
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state: $state")
            }
            
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    // 通过 WebSocket 发送 ICE candidate
                    sendSignal("candidate", mapOf(
                        "candidate" to mapOf(
                            "candidate" to it.sdp,
                            "sdpMid" to it.sdpMid,
                            "sdpMLineIndex" to it.sdpMLineIndex
                        )
                    ))
                }
            }
            
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                // 处理移除的 candidates
            }
            
            override fun onAddStream(stream: MediaStream?) {
                // 已废弃，使用 onTrack
            }
            
            override fun onRemoveStream(stream: MediaStream?) {
                // 已废弃
            }
            
            override fun onDataChannel(channel: DataChannel?) {
                Log.d(TAG, "DataChannel received: ${channel?.label()}")
                dataChannel = channel
                setupDataChannel(channel)
            }
            
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }
            
            override fun onAddTrack(
                receiver: RtpReceiver?,
                streams: Array<out MediaStream>?
            ) {
                Log.d(TAG, "Track added")
                streams?.firstOrNull()?.let { stream ->
                    // 获取视频轨道
                    stream.videoTracks.firstOrNull()?.let { track ->
                        track.addSink(remoteVideoView)
                    }
                }
            }
        }
    )!!
}
```

---

## 连接流程

### 完整流程图

```
1. 初始化 WebRTC
   ↓
2. 连接 WebSocket
   ↓
3. 发送 join 消息
   ↓
4. 收到 join-ack
   ↓
5. 判断 participants
   ├─ > 1 → 立即创建 offer
   └─ = 1 → 等待 peer-joined → 创建 offer
   ↓
6. 创建 offer，设置本地描述
   ↓
7. 通过 WebSocket 发送 offer
   ↓
8. 收到 answer，设置远端描述
   ↓
9. 交换 ICE candidates
   ↓
10. 建立连接，接收视频流
    ↓
11. 创建/接收 DataChannel
    ↓
12. 开始控制交互
```

### 代码实现

```kotlin
class RemoteControlManager {
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var isInitiator = false
    private var isJoined = false
    
    // 1. 加入房间
    fun joinRoom(roomId: String, sender: String) {
        webSocket.send(JSONObject().apply {
            put("type", "join")
            put("roomId", roomId)
            put("sender", sender)
        }.toString())
    }
    
    // 2. 处理 join-ack
    private fun handleJoinAck(data: JSONObject) {
        val participants = data.optJSONObject("data")
            ?.optInt("participants", 1) ?: 1
        
        isJoined = true
        
        if (participants > 1) {
            // 房间已有成员，主动创建 offer
            isInitiator = true
            createOffer()
        }
    }
    
    // 3. 处理 peer-joined
    private fun handlePeerJoined() {
        isInitiator = true
        createOffer()
    }
    
    // 4. 创建 Offer
    private fun createOffer() {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            // 发送 offer
                            sendSignal("offer", mapOf(
                                "sdp" to mapOf(
                                    "type" to it.type.canonicalForm(),
                                    "sdp" to it.description
                                )
                            ))
                        }
                        
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set local description failed: $error")
                        }
                        
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    }, it)
                }
            }
            
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }
    
    // 5. 处理收到的 Offer
    private fun handleOffer(data: JSONObject) {
        val sdpData = data.getJSONObject("data").getJSONObject("sdp")
        val sdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdpData.getString("type")),
            sdpData.getString("sdp")
        )
        
        peerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    createAnswer()
                }
                
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Set remote description failed: $error")
                }
                
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            },
            sdp
        )
    }
    
    // 6. 创建 Answer
    private fun createAnswer() {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            sendSignal("answer", mapOf(
                                "sdp" to mapOf(
                                    "type" to it.type.canonicalForm(),
                                    "sdp" to it.description
                                )
                            ))
                        }
                        
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set local description failed: $error")
                        }
                        
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    }, it)
                }
            }
            
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create answer failed: $error")
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }
    
    // 7. 处理收到的 Answer
    private fun handleAnswer(data: JSONObject) {
        val sdpData = data.getJSONObject("data").getJSONObject("sdp")
        val sdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdpData.getString("type")),
            sdpData.getString("sdp")
        )
        
        peerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "Answer set successfully")
                }
                
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Set remote description failed: $error")
                }
                
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            },
            sdp
        )
    }
    
    // 8. 处理 ICE Candidate
    private fun handleCandidate(data: JSONObject) {
        val candidateData = data.getJSONObject("data").getJSONObject("candidate")
        val candidate = IceCandidate(
            candidateData.getString("sdpMid"),
            candidateData.getInt("sdpMLineIndex"),
            candidateData.getString("candidate")
        )
        
        peerConnection?.addIceCandidate(candidate)
    }
}
```

---

## 消息格式详解

### WebSocket 消息发送函数

```kotlin
private fun sendSignal(type: String, data: Map<String, Any>) {
    if (webSocket == null || webSocket?.isClosed == true) {
        Log.w(TAG, "WebSocket not connected")
        return
    }
    
    val message = JSONObject().apply {
        put("type", type)
        put("roomId", roomId)
        put("sender", senderName)
        put("data", JSONObject(data))
    }
    
    webSocket?.send(message.toString())
}
```

### 消息接收处理

```kotlin
private fun handleWebSocketMessage(message: String) {
    try {
        val json = JSONObject(message)
        val type = json.getString("type")
        
        when (type) {
            "join-ack" -> handleJoinAck(json)
            "peer-joined" -> handlePeerJoined()
            "peer-left" -> handlePeerLeft()
            "offer" -> handleOffer(json)
            "answer" -> handleAnswer(json)
            "candidate" -> handleCandidate(json)
            "pong" -> handlePong()
            "error" -> handleError(json)
            else -> Log.w(TAG, "Unknown message type: $type")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse message: $e")
    }
}
```

---

## 数据通道协议

### 创建 DataChannel

```kotlin
private fun createDataChannel() {
    val init = DataChannel.Init().apply {
        ordered = true
        maxRetransmits = -1  // 无限重传
        maxRetransmitTimeMs = -1
    }
    
    dataChannel = peerConnection?.createDataChannel("control", init)
    setupDataChannel(dataChannel)
}

private fun setupDataChannel(channel: DataChannel?) {
    channel?.registerObserver(object : DataChannel.Observer {
        override fun onBufferedAmountChange(amount: Long) {
            // 缓冲区变化
        }
        
        override fun onStateChange() {
            val state = channel.state()
            Log.d(TAG, "DataChannel state: $state")
            
            when (state) {
                DataChannel.State.OPEN -> {
                    Log.d(TAG, "DataChannel opened")
                }
                DataChannel.State.CLOSED -> {
                    Log.d(TAG, "DataChannel closed")
                }
                else -> {}
            }
        }
        
        override fun onMessage(buffer: DataChannel.Buffer?) {
            buffer?.let {
                val data = String(it.data.array(), Charsets.UTF_8)
                handleDataChannelMessage(data)
            }
        }
    })
}
```

### 控制消息格式

#### 鼠标消息

```kotlin
// 鼠标移动
fun sendMouseMove(xRatio: Float, yRatio: Float) {
    sendControlMessage(mapOf(
        "kind" to "mouse",
        "action" to "move",
        "xRatio" to xRatio,
        "yRatio" to yRatio
    ))
}

// 鼠标按下
fun sendMouseDown(xRatio: Float, yRatio: Float, button: Int) {
    sendControlMessage(mapOf(
        "kind" to "mouse",
        "action" to "mousedown",
        "xRatio" to xRatio,
        "yRatio" to yRatio,
        "button" to button  // 0=左键, 1=中键, 2=右键
    ))
}

// 鼠标释放
fun sendMouseUp(xRatio: Float, yRatio: Float, button: Int) {
    sendControlMessage(mapOf(
        "kind" to "mouse",
        "action" to "mouseup",
        "xRatio" to xRatio,
        "yRatio" to yRatio,
        "button" to button
    ))
}

// 双击
fun sendMouseDoubleClick(xRatio: Float, yRatio: Float, button: Int) {
    sendControlMessage(mapOf(
        "kind" to "mouse",
        "action" to "dblclick",
        "xRatio" to xRatio,
        "yRatio" to yRatio,
        "button" to button
    ))
}

// 滚轮
fun sendMouseWheel(xRatio: Float, yRatio: Float, deltaY: Float) {
    sendControlMessage(mapOf(
        "kind" to "mouse",
        "action" to "wheel",
        "xRatio" to xRatio,
        "yRatio" to yRatio,
        "deltaY" to deltaY
    ))
}
```

#### 键盘消息

```kotlin
fun sendKeyboardEvent(
    type: String,  // "keydown" 或 "keyup"
    key: String,   // 例如 "a", "Enter", "ArrowUp"
    code: String,  // 例如 "KeyA", "Enter", "ArrowUp"
    altKey: Boolean = false,
    ctrlKey: Boolean = false,
    metaKey: Boolean = false,
    shiftKey: Boolean = false,
    repeat: Boolean = false
) {
    sendControlMessage(mapOf(
        "kind" to "keyboard",
        "type" to type,
        "key" to key,
        "code" to code,
        "altKey" to altKey,
        "ctrlKey" to ctrlKey,
        "metaKey" to metaKey,
        "shiftKey" to shiftKey,
        "repeat" to repeat
    ))
}
```

#### 聊天消息

```kotlin
fun sendChatMessage(text: String) {
    sendControlMessage(mapOf(
        "kind" to "chat",
        "sender" to senderName,
        "text" to text
    ))
}
```

#### 发送控制消息

```kotlin
private fun sendControlMessage(payload: Map<String, Any>) {
    val message = JSONObject(payload).toString()
    
    // 优先使用 DataChannel
    if (dataChannel?.state() == DataChannel.State.OPEN) {
        try {
            val buffer = ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8))
            dataChannel?.send(DataChannel.Buffer(buffer, false))
        } catch (e: Exception) {
            Log.e(TAG, "DataChannel send failed, fallback to WebSocket: $e")
            // 降级使用 WebSocket
            sendSignal("control", payload)
        }
    } else {
        // 降级使用 WebSocket
        sendSignal("control", payload)
    }
}
```

### 接收数据通道消息

```kotlin
private fun handleDataChannelMessage(data: String) {
    try {
        val json = JSONObject(data)
        val kind = json.getString("kind")
        
        when (kind) {
            "chat" -> {
                val sender = json.optString("sender", "peer")
                val text = json.getString("text")
                // 显示聊天消息
                onChatMessageReceived(sender, text)
            }
            "mouse" -> {
                // 鼠标消息通常不需要响应（用于显示远程光标）
                // 如果需要显示远程光标位置，可以处理
            }
            "keyboard" -> {
                // 键盘消息通常不需要响应
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse data channel message: $e")
    }
}
```

---

## 完整代码示例

### RemoteControlManager.kt

```kotlin
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class RemoteControlManager(
    private val serverUrl: String,
    private val roomId: String,
    private val senderName: String,
    private val remoteVideoView: SurfaceViewRenderer,
    private val peerConnectionFactory: PeerConnectionFactory
) {
    companion object {
        private const val TAG = "RemoteControlManager"
        private const val HEARTBEAT_INTERVAL = 30000L  // 30秒
    }
    
    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var isInitiator = false
    private var isJoined = false
    private var heartbeatJob: Job? = null
    
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    
    // 初始化
    fun initialize() {
        connectWebSocket()
    }
    
    // 连接 WebSocket
    private fun connectWebSocket() {
        val request = Request.Builder()
            .url("$serverUrl/ws")
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                joinRoom()
                startHeartbeat()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleWebSocketMessage(bytes.utf8())
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed")
                stopHeartbeat()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                // 实现重连逻辑
            }
        })
    }
    
    // 加入房间
    private fun joinRoom() {
        sendSignal("join", emptyMap())
    }
    
    // 发送信令消息
    private fun sendSignal(type: String, data: Map<String, Any>) {
        val message = JSONObject().apply {
            put("type", type)
            put("roomId", roomId)
            put("sender", senderName)
            if (data.isNotEmpty()) {
                put("data", JSONObject(data))
            }
        }
        webSocket?.send(message.toString())
    }
    
    // 处理 WebSocket 消息
    private fun handleWebSocketMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")
            
            when (type) {
                "join-ack" -> {
                    val participants = json.optJSONObject("data")
                        ?.optInt("participants", 1) ?: 1
                    isJoined = true
                    
                    if (participants > 1) {
                        isInitiator = true
                        createPeerConnection()
                        createOffer()
                    }
                }
                "peer-joined" -> {
                    isInitiator = true
                    createPeerConnection()
                    createOffer()
                }
                "peer-left" -> {
                    cleanupPeerConnection()
                }
                "offer" -> {
                    handleOffer(json)
                }
                "answer" -> {
                    handleAnswer(json)
                }
                "candidate" -> {
                    handleCandidate(json)
                }
                "pong" -> {
                    // 心跳响应
                }
                "error" -> {
                    val errorMsg = json.optJSONObject("data")
                        ?.optString("message", "Unknown error") ?: "Unknown error"
                    Log.e(TAG, "Server error: $errorMsg")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $e")
        }
    }
    
    // 创建 PeerConnection
    private fun createPeerConnection() {
        if (peerConnection != null) return
        
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )
        
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportPolicy = PeerConnection.IceTransportPolicy.ALL
            bundlePolicy = PeerConnection.BundlePolicy.BALANCED
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceCandidatePoolSize = 0
        }
        
        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling: $state")
                }
                
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            // 等待恢复
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            peerConnection?.restartIce()
                        }
                        else -> {}
                    }
                }
                
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE gathering: $state")
                }
                
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        sendSignal("candidate", mapOf(
                            "candidate" to mapOf(
                                "candidate" to it.sdp,
                                "sdpMid" to it.sdpMid,
                                "sdpMLineIndex" to it.sdpMLineIndex
                            )
                        ))
                    }
                }
                
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                
                override fun onDataChannel(channel: DataChannel?) {
                    Log.d(TAG, "DataChannel received")
                    dataChannel = channel
                    setupDataChannel(channel)
                }
                
                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed")
                }
                
                override fun onAddTrack(
                    receiver: RtpReceiver?,
                    streams: Array<out MediaStream>?
                ) {
                    streams?.firstOrNull()?.videoTracks?.firstOrNull()?.let { track ->
                        track.addSink(remoteVideoView)
                    }
                }
            }
        )
        
        // 创建 DataChannel
        val init = DataChannel.Init().apply {
            ordered = true
        }
        dataChannel = peerConnection?.createDataChannel("control", init)
        setupDataChannel(dataChannel)
    }
    
    // 创建 Offer
    private fun createOffer() {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(
                        object : SdpObserver {
                            override fun onSetSuccess() {
                                sendSignal("offer", mapOf(
                                    "sdp" to mapOf(
                                        "type" to it.type.canonicalForm(),
                                        "sdp" to it.description
                                    )
                                ))
                            }
                            
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "Set local desc failed: $error")
                            }
                            
                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                        },
                        it
                    )
                }
            }
            
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }
    
    // 处理 Offer
    private fun handleOffer(json: JSONObject) {
        val sdpData = json.getJSONObject("data").getJSONObject("sdp")
        val sdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdpData.getString("type")),
            sdpData.getString("sdp")
        )
        
        peerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    createAnswer()
                }
                
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Set remote desc failed: $error")
                }
                
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            },
            sdp
        )
    }
    
    // 创建 Answer
    private fun createAnswer() {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(
                        object : SdpObserver {
                            override fun onSetSuccess() {
                                sendSignal("answer", mapOf(
                                    "sdp" to mapOf(
                                        "type" to it.type.canonicalForm(),
                                        "sdp" to it.description
                                    )
                                ))
                            }
                            
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "Set local desc failed: $error")
                            }
                            
                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                        },
                        it
                    )
                }
            }
            
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create answer failed: $error")
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }
    
    // 处理 Answer
    private fun handleAnswer(json: JSONObject) {
        val sdpData = json.getJSONObject("data").getJSONObject("sdp")
        val sdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdpData.getString("type")),
            sdpData.getString("sdp")
        )
        
        peerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "Answer set successfully")
                }
                
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Set remote desc failed: $error")
                }
                
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            },
            sdp
        )
    }
    
    // 处理 Candidate
    private fun handleCandidate(json: JSONObject) {
        val candidateData = json.getJSONObject("data").getJSONObject("candidate")
        val candidate = IceCandidate(
            candidateData.getString("sdpMid"),
            candidateData.getInt("sdpMLineIndex"),
            candidateData.getString("candidate")
        )
        
        peerConnection?.addIceCandidate(candidate)
    }
    
    // 设置 DataChannel
    private fun setupDataChannel(channel: DataChannel?) {
        channel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}
            
            override fun onStateChange() {
                Log.d(TAG, "DataChannel state: ${channel.state()}")
            }
            
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer?.let {
                    val data = String(it.data.array(), Charsets.UTF_8)
                    Log.d(TAG, "DataChannel message: $data")
                }
            }
        })
    }
    
    // 发送鼠标事件
    fun sendMouseEvent(action: String, xRatio: Float, yRatio: Float, button: Int = 0) {
        sendControlMessage(mapOf(
            "kind" to "mouse",
            "action" to action,
            "xRatio" to xRatio,
            "yRatio" to yRatio,
            "button" to button
        ))
    }
    
    // 发送键盘事件
    fun sendKeyboardEvent(
        type: String,
        key: String,
        code: String,
        modifiers: Map<String, Boolean> = emptyMap()
    ) {
        sendControlMessage(mapOf(
            "kind" to "keyboard",
            "type" to type,
            "key" to key,
            "code" to code,
            "altKey" to (modifiers["alt"] ?: false),
            "ctrlKey" to (modifiers["ctrl"] ?: false),
            "metaKey" to (modifiers["meta"] ?: false),
            "shiftKey" to (modifiers["shift"] ?: false)
        ))
    }
    
    // 发送控制消息
    private fun sendControlMessage(payload: Map<String, Any>) {
        val message = JSONObject(payload).toString()
        
        if (dataChannel?.state() == DataChannel.State.OPEN) {
            try {
                val buffer = ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8))
                dataChannel?.send(DataChannel.Buffer(buffer, false))
            } catch (e: Exception) {
                Log.e(TAG, "DataChannel send failed: $e")
                sendSignal("control", payload)
            }
        } else {
            sendSignal("control", payload)
        }
    }
    
    // 心跳
    private fun startHeartbeat() {
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                sendSignal("ping", emptyMap())
            }
        }
    }
    
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
    }
    
    // 清理
    private fun cleanupPeerConnection() {
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        peerConnection = null
    }
    
    fun cleanup() {
        stopHeartbeat()
        sendSignal("leave", emptyMap())
        cleanupPeerConnection()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
    }
}
```

---

## 注意事项

### 1. 坐标转换

Android 端需要将触摸坐标转换为相对于视频的比例坐标（0-1）：

```kotlin
fun calculateRatio(x: Float, y: Float, videoView: View): Pair<Float, Float>? {
    val videoWidth = videoView.width.toFloat()
    val videoHeight = videoView.height.toFloat()
    
    if (videoWidth == 0f || videoHeight == 0f) return null
    
    // 计算视频实际显示区域（考虑 object-fit: contain）
    val videoAspect = videoWidth / videoHeight
    val viewAspect = videoView.width.toFloat() / videoView.height.toFloat()
    
    val displayWidth: Float
    val displayHeight: Float
    val offsetX: Float
    val offsetY: Float
    
    if (videoAspect > viewAspect) {
        // 视频更宽，上下留黑边
        displayWidth = videoView.width.toFloat()
        displayHeight = displayWidth / videoAspect
        offsetX = 0f
        offsetY = (videoView.height - displayHeight) / 2
    } else {
        // 视频更高，左右留黑边
        displayHeight = videoView.height.toFloat()
        displayWidth = displayHeight * videoAspect
        offsetX = (videoView.width - displayWidth) / 2
        offsetY = 0f
    }
    
    // 转换为相对于视频的比例
    val relativeX = (x - offsetX) / displayWidth
    val relativeY = (y - offsetY) / displayHeight
    
    if (relativeX < 0 || relativeX > 1 || relativeY < 0 || relativeY > 1) {
        return null  // 坐标在视频区域外
    }
    
    return Pair(relativeX, relativeY)
}
```

### 2. 线程安全

WebRTC 操作需要在主线程执行，但网络操作应该在后台线程：

```kotlin
// WebSocket 消息处理
private fun handleWebSocketMessage(message: String) {
    // 切换到主线程处理 WebRTC 相关操作
    Handler(Looper.getMainLooper()).post {
        // WebRTC 操作
    }
}
```

### 3. 生命周期管理

在 Activity/Fragment 的生命周期中正确管理资源：

```kotlin
override fun onPause() {
    super.onPause()
    // 暂停视频渲染
}

override fun onResume() {
    super.onResume()
    // 恢复视频渲染
}

override fun onDestroy() {
    super.onDestroy()
    remoteControlManager.cleanup()
}
```

### 4. 错误处理

实现完善的错误处理和重连机制：

```kotlin
// WebSocket 重连
private fun reconnectWebSocket() {
    if (isReconnecting) return
    isReconnecting = true
    
    var delay = 1000L
    val maxDelay = 10000L
    
    while (webSocket == null || webSocket?.isClosed == true) {
        delay(delay)
        connectWebSocket()
        delay = minOf(delay * 2, maxDelay)
    }
    
    isReconnecting = false
}
```

### 5. 性能优化

- 使用 `SurfaceViewRenderer` 而不是 `TextureView` 以获得更好的性能
- 限制鼠标移动事件的发送频率（节流）
- 使用 DataChannel 而不是 WebSocket 发送控制消息（更低延迟）

### 6. 测试建议

1. 先在局域网环境测试
2. 测试不同网络环境（WiFi、4G、5G）
3. 测试弱网情况下的重连机制
4. 测试不同分辨率和帧率的视频流

---

## 总结

按照本手册的步骤，你可以成功将 Android 应用接入 WebRTC 远程控制系统。关键点：

1. ✅ 正确配置 WebRTC 和 WebSocket
2. ✅ 实现完整的信令流程
3. ✅ 处理 ICE candidates 交换
4. ✅ 实现数据通道通信
5. ✅ 正确处理坐标转换
6. ✅ 实现错误处理和重连机制

如有问题，请参考项目源码或提交 Issue。

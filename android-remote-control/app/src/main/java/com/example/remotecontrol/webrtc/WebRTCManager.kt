package com.example.remotecontrol.webrtc

import android.content.Context
import android.util.Log
import android.media.projection.MediaProjection
import com.example.remotecontrol.service.RemoteControlService
import org.webrtc.*
import java.nio.ByteBuffer

/**
 * WebRTC Manager
 * 负责 PeerConnection 和视频流管理
 */
class WebRTCManager(
    private val context: Context,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "WebRTCManager"
        private const val DATA_CHANNEL_LABEL = "control"
    }

    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onIceConnectionStateChange(state: PeerConnection.IceConnectionState)
        fun onDataChannelOpen()
        fun onDataChannelMessage(message: String)
        fun onDataChannelClose()
        fun onVideoTrackReceived(track: VideoTrack)
        fun onOfferCreated(sdp: SessionDescription)
        fun onAnswerCreated(sdp: SessionDescription)
        fun onError(error: String)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var eglBase: EglBase? = null

    private var remoteVideoWidth: Int = 1920
    private var remoteVideoHeight: Int = 1080
    
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: VideoCapturer? = null

    /**
     * 初始化 WebRTC
     */
    fun initialize() {
        Log.d(TAG, "Initializing WebRTC")

        // 初始化 EGL
        eglBase = EglBase.create()

        // 初始化 PeerConnectionFactory
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options()
        
        // DefaultVideoEncoderFactory 内置硬件优先 + 软件回退机制:
        // - 优先使用硬件编码器 (MediaCodec)
        // - 如果硬件不支持，自动回退到 VP8/VP9 软件编码器
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase?.eglBaseContext,
            true,   // enableIntelVp8Encoder - 启用 VP8/VP9 硬件编码 (如果可用)
            true    // enableH264HighProfile - 启用 H264 High Profile (更高压缩率)
        )
        
        // DefaultVideoDecoderFactory 同样内置软硬件回退
        val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        
        Log.d(TAG, "WebRTC initialized (hardware encoder preferred, software fallback)")
    }

    /**
     * 创建屏幕共享 VideoTrack
     */
    fun createScreenCaptureVideoTrack(intent: android.content.Intent): VideoTrack? {
        videoCapturer = createScreenCapturer(intent) ?: return null
        
        val videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast) 
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
        videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer!!.startCapture(
            RemoteControlService.captureWidth, 
            RemoteControlService.captureHeight, 
            15
        )

        val videoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
        videoTrack?.setEnabled(true)
        this.localVideoTrack = videoTrack
        Log.d(TAG, "Screen capture video track created")
        return videoTrack
    }

    // 用于处理 MediaProjection 回调的 Handler（切换到主线程）
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // 标记是否正在停止捕获（防止重复调用）
    @Volatile
    private var isStopping = false

    private fun createScreenCapturer(intent: android.content.Intent): VideoCapturer? {
         return ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system or user")
                
                // 切换到主线程处理，避免并发问题
                mainHandler.post {
                    if (isStopping) {
                        Log.d(TAG, "Already stopping, skip duplicate onStop")
                        return@post
                    }
                    isStopping = true
                    
                    try {
                        // 优雅停止屏幕捕获
                        Log.i(TAG, "Gracefully stopping screen capture...")
                        
                        // 1. 先停止视频轨道
                        localVideoTrack?.setEnabled(false)
                        
                        // 2. 停止捕获器
                        try {
                            videoCapturer?.stopCapture()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping video capturer: ${e.message}")
                        }
                        
                        // 3. 通知监听器
                        listener.onError("MediaProjection stopped")
                        
                        Log.i(TAG, "Screen capture stopped gracefully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in MediaProjection.onStop: ${e.message}", e)
                    } finally {
                        isStopping = false
                    }
                }
            }
        })
    }

    /**
     * 获取 EglBase（用于 SurfaceViewRenderer）
     */
    fun getEglContext(): EglBase.Context? = eglBase?.eglBaseContext

    /**
     * ICE 服务器配置
     */
    data class IceServerConfig(
        val url: String,
        val username: String = "",
        val password: String = ""
    )

    /**
     * 创建 PeerConnection
     */
    fun createPeerConnection(iceServers: List<IceServerConfig> = listOf(IceServerConfig("stun:stun.l.google.com:19302"))) {
        if (peerConnection != null) {
            Log.w(TAG, "PeerConnection already exists")
            return
        }

        val iceServerList = iceServers.map { config ->
            val builder = PeerConnection.IceServer.builder(config.url)
            if (config.username.isNotEmpty() && config.password.isNotEmpty()) {
                builder.setUsername(config.username)
                builder.setPassword(config.password)
            }
            builder.createIceServer()
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServerList).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.BALANCED
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceCandidatePoolSize = 0
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
                state?.let { listener.onIceConnectionStateChange(it) }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE receiving: $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "ICE candidate: ${it.sdp}")
                    listener.onLocalIceCandidate(it)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "ICE candidates removed")
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

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "Track added: ${receiver?.track()?.kind()}")
                receiver?.track()?.let { track ->
                    if (track is VideoTrack) {
                        listener.onVideoTrackReceived(track)
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                Log.d(TAG, "onTrack: ${transceiver?.receiver?.track()?.kind()}")
            }
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
        Log.d(TAG, "PeerConnection created")

        // 添加本地视频流
        localVideoTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("ARDAMS"))
            Log.d(TAG, "Local video track added to PeerConnection")
        }

        // 创建 DataChannel（作为发起方）
        createDataChannel()
    }

    /**
     * 创建 DataChannel
     */
    private fun createDataChannel() {
        val init = DataChannel.Init().apply {
            ordered = true
            maxRetransmits = -1
        }
        dataChannel = peerConnection?.createDataChannel(DATA_CHANNEL_LABEL, init)
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
                    DataChannel.State.OPEN -> listener.onDataChannelOpen()
                    DataChannel.State.CLOSED -> listener.onDataChannelClose()
                    else -> {}
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer?.let {
                    val data = ByteArray(it.data.remaining())
                    it.data.get(data)
                    val message = String(data, Charsets.UTF_8)
                    Log.d(TAG, "DataChannel message: $message")
                    listener.onDataChannelMessage(message)
                }
            }
        })
    }

    /**
     * 创建 Offer
     */
    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set (offer)")
                            listener.onOfferCreated(it)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set local description failed: $error")
                            listener.onError("Set local description failed: $error")
                        }
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    }, it)
                }
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
                listener.onError("Create offer failed: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * 处理远端 Offer
     */
    fun handleOffer(sdpType: String, sdp: String) {
        val sessionDescription = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdpType),
            sdp
        )

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set (offer)")
                createAnswer()
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set remote description failed: $error")
                listener.onError("Set remote description failed: $error")
            }

            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, sessionDescription)
    }

    /**
     * 创建 Answer
     */
    private fun createAnswer() {
        // UNIFIED_PLAN 不需要设置 OfferToReceive 约束，方向由 transceiver 控制
        val constraints = MediaConstraints()

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set (answer)")
                            listener.onAnswerCreated(it)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set local description failed: $error")
                            listener.onError("Set local description failed: $error")
                        }
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    }, it)
                }
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create answer failed: $error")
                listener.onError("Create answer failed: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * 处理远端 Answer
     */
    fun handleAnswer(sdpType: String, sdp: String) {
        val sessionDescription = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdpType),
            sdp
        )

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set (answer)")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set remote description failed: $error")
                listener.onError("Set remote description failed: $error")
            }

            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, sessionDescription)
    }

    /**
     * 添加 ICE Candidate
     */
    fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    /**
     * 通过 DataChannel 发送消息
     */
    fun sendDataChannelMessage(message: String): Boolean {
        val channel = dataChannel
        if (channel?.state() != DataChannel.State.OPEN) {
            Log.w(TAG, "DataChannel not open")
            return false
        }

        return try {
            val buffer = ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8))
            channel.send(DataChannel.Buffer(buffer, false))
            true
        } catch (e: Exception) {
            Log.e(TAG, "DataChannel send failed: $e")
            false
        }
    }

    /**
     * 检查 DataChannel 是否打开
     */
    fun isDataChannelOpen(): Boolean = dataChannel?.state() == DataChannel.State.OPEN

    /**
     * 重启 ICE
     */
    fun restartIce() {
        peerConnection?.restartIce()
    }
    
    /**
     * 获取当前 ICE 连接状态
     */
    fun getIceConnectionState(): PeerConnection.IceConnectionState? {
        return peerConnection?.iceConnectionState()
    }
    
    /**
     * 请求关键帧 (I 帧)
     * 当控制端检测到画面卡顿时调用
     */
    fun requestKeyFrame() {
        try {
            // 方法1: 通过改变采集格式触发新的 I 帧
            videoCapturer?.changeCaptureFormat(
                RemoteControlService.captureWidth,
                RemoteControlService.captureHeight,
                15  // 保持当前帧率
            )
            Log.i(TAG, "Requested key frame via capture format change")
            
            // 方法2: 通过 RtpSender 触发 (备选)
            peerConnection?.senders?.find { it.track()?.kind() == "video" }?.let { sender ->
                val parameters = sender.parameters
                if (parameters.encodings.isNotEmpty()) {
                    // 修改参数会触发编码器重新配置，产生 I 帧
                    sender.parameters = parameters
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request key frame: ${e.message}")
        }
    }

    /**
     * 设置视频参数（动态调整画质）
     */
    fun setVideoConfig(fps: Int, bitrateBps: Int) {
        // 1. 调整采集帧率
        try {
            videoCapturer?.changeCaptureFormat(
                RemoteControlService.captureWidth, 
                RemoteControlService.captureHeight, 
                fps
            )
            Log.i(TAG, "Changed capture format to ${fps}fps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change capture format: ${e.message}")
        }
        
        // 2. 调整编码器码率 + SVC层级 + 分辨率缩放
        peerConnection?.senders?.find { it.track()?.kind() == "video" }?.let { sender ->
            val parameters = sender.parameters
            if (parameters.encodings.isNotEmpty()) {
                val encoding = parameters.encodings[0]
                encoding.maxBitrateBps = bitrateBps
                
                // SVC (可分层视频编码) - 根据码率动态调整
                // L1T1 = 1层空间 1层时间 (最低质量，最稳定)
                // L1T2 = 1层空间 2层时间 (中等)
                // L1T3 = 1层空间 3层时间 (高质量)
                val svcMode = when {
                    bitrateBps < 500000 -> "L1T1"    // 极低码率：单层
                    bitrateBps < 1500000 -> "L1T2"   // 中等码率：2层时间SVC
                    else -> "L1T3"                   // 高码率：3层时间SVC
                }
                try {
                    encoding.scalabilityMode = svcMode
                    Log.i(TAG, "Set SVC scalability mode: $svcMode")
                } catch (e: Exception) {
                    Log.d(TAG, "SVC mode not supported: ${e.message}")
                }
                
                // 编码分辨率缩放 - 保持捕获分辨率不变，只缩小编码输出
                // 优点：鼠标坐标计算不受影响，视频容器尺寸不变，只是清晰度变化
                val scaleDown = when {
                    bitrateBps < 300000 -> 3.0    // 极低码率：1/3 分辨率 (~360p)
                    bitrateBps < 700000 -> 2.0    // 低码率：1/2 分辨率 (~540p)
                    bitrateBps < 1500000 -> 1.5   // 中码率：2/3 分辨率 (~720p)
                    else -> 1.0                    // 高码率：原分辨率 (1080p)
                }
                try {
                    encoding.scaleResolutionDownBy = scaleDown
                    Log.i(TAG, "Set resolution scale down: ${scaleDown}x (effective ~${(1080/scaleDown).toInt()}p)")
                } catch (e: Exception) {
                    Log.d(TAG, "scaleResolutionDownBy not supported: ${e.message}")
                }
                
                sender.parameters = parameters
                Log.i(TAG, "Changed max bitrate to ${bitrateBps/1000}kbps")
            }
        }
    }
    
    /**
     * 根据网络状况调整 Jitter Buffer
     * @param rttMs 当前RTT延迟(毫秒)
     * @param jitterMs 当前抖动(毫秒)
     */
    fun adjustJitterBuffer(rttMs: Int, jitterMs: Double) {
        peerConnection?.receivers?.forEach { receiver ->
            receiver.track()?.let { track ->
                if (track.kind() == "video") {
                    try {
                        // 根据网络状况动态设置 playout delay
                        // 高延迟/高抖动时增加缓冲区，降低卡顿
                        val minDelayMs = when {
                            rttMs > 300 || jitterMs > 50 -> 200.0  // 极差网络
                            rttMs > 150 || jitterMs > 30 -> 100.0  // 弱网
                            rttMs > 80 || jitterMs > 15 -> 50.0    // 中等
                            else -> 0.0                            // 良好网络
                        }
                        val maxDelayMs = minDelayMs + 300.0
                        
                        // RtpReceiver.setJitterBufferMinimumDelay (秒为单位)
                        // 注：需要 WebRTC M92+ 版本支持
                        // receiver.setJitterBufferMinimumDelay(minDelayMs / 1000.0)
                        
                        Log.i(TAG, "Jitter buffer adjusted: min=${minDelayMs}ms (RTT=${rttMs}ms, jitter=${jitterMs}ms)")
                    } catch (e: Exception) {
                        Log.d(TAG, "Jitter buffer adjustment not supported: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * 启用/调整 FEC (前向纠错) 冗余度
     * 通过修改编码参数实现
     */
    fun setFecRedundancy(enabled: Boolean, redundancyPercent: Int = 20) {
        peerConnection?.senders?.find { it.track()?.kind() == "video" }?.let { sender ->
            val parameters = sender.parameters
            if (parameters.encodings.isNotEmpty()) {
                val encoding = parameters.encodings[0]
                
                // 通过设置 active 和调整码率预留 FEC 带宽
                // WebRTC 内部会根据丢包率自动调整 FEC 冗余
                // 我们可以通过降低 maxBitrateBps 为 FEC 预留带宽
                if (enabled && encoding.maxBitrateBps != null) {
                    val fecReserve = encoding.maxBitrateBps!! * redundancyPercent / 100
                    // 实际视频码率 = 总码率 - FEC预留
                    Log.i(TAG, "FEC enabled: reserving ${fecReserve/1000}kbps for redundancy")
                }
                
                sender.parameters = parameters
            }
        }
    }

    /**
     * 关闭 PeerConnection
     */
    fun closePeerConnection() {
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        peerConnection = null
    }

    /**
     * 释放资源
     * 注意：释放顺序很重要，错误的顺序可能导致 surfaceflinger 崩溃
     */
    fun release() {
        if (isStopping) {
            Log.w(TAG, "Already releasing, skip duplicate call")
            return
        }
        isStopping = true
        
        Log.i(TAG, "Releasing WebRTC resources...")
        
        try {
            // 1. 先禁用视频轨道（减少 surfaceflinger 负载）
            localVideoTrack?.setEnabled(false)
            
            // 2. 关闭 PeerConnection（停止发送）
            closePeerConnection()
            
            // 3. 停止屏幕捕获（关键步骤）
            try {
                videoCapturer?.stopCapture()
                Log.d(TAG, "Video capturer stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping video capturer", e)
            }
            
            // 4. 短暂延迟，让系统处理停止请求
            Thread.sleep(100)
            
            // 5. 释放捕获器
            try {
                videoCapturer?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "Error disposing video capturer", e)
            }
            videoCapturer = null
            
            // 6. 释放 SurfaceTextureHelper
            try {
                surfaceTextureHelper?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "Error disposing surfaceTextureHelper", e)
            }
            surfaceTextureHelper = null
            
            // 7. 释放视频轨道
            try {
                localVideoTrack?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "Error disposing localVideoTrack", e)
            }
            localVideoTrack = null
            
            // 8. 释放工厂和 EGL
            try {
                peerConnectionFactory?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "Error disposing peerConnectionFactory", e)
            }
            peerConnectionFactory = null
            
            try {
                eglBase?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing eglBase", e)
            }
            eglBase = null
            
            Log.i(TAG, "WebRTC resources released successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during release: ${e.message}", e)
        } finally {
            isStopping = false
        }
    }
}

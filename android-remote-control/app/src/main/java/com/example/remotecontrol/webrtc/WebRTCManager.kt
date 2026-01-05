package com.example.remotecontrol.webrtc

import android.content.Context
import android.util.Log
import android.media.projection.MediaProjection
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
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase?.eglBaseContext,
            false,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        
        Log.d(TAG, "WebRTC initialized")
    }

    /**
     * 创建屏幕共享 VideoTrack
     */
    fun createScreenCaptureVideoTrack(intent: android.content.Intent): VideoTrack? {
        videoCapturer = createScreenCapturer(intent) ?: return null
        
        val videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast) 
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
        videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer!!.startCapture(remoteVideoWidth, remoteVideoHeight, 15)

        val videoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
        videoTrack?.setEnabled(true)
        this.localVideoTrack = videoTrack
        Log.d(TAG, "Screen capture video track created")
        return videoTrack
    }

    private fun createScreenCapturer(intent: android.content.Intent): VideoCapturer? {
         return ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.e(TAG, "User revoked permission to capture the screen.")
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
     */
    fun release() {
        closePeerConnection()
        
        // 释放屏幕捕获资源
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping video capturer", e)
        }
        videoCapturer?.dispose()
        videoCapturer = null
        
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        
        localVideoTrack?.dispose()
        localVideoTrack = null
        
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
    }
}

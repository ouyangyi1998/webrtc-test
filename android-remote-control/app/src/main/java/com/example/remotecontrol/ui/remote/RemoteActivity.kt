package com.example.remotecontrol.ui.remote

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.remotecontrol.control.RemoteControlManager
import com.example.remotecontrol.databinding.ActivityRemoteBinding
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

/**
 * 远程控制主界面
 */
class RemoteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_NICKNAME = "nickname"
    }

    private lateinit var binding: ActivityRemoteBinding
    private var remoteControlManager: RemoteControlManager? = null
    private var currentVideoTrack: VideoTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityRemoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 获取连接参数
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: return finish()
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: return finish()
        val nickname = intent.getStringExtra(EXTRA_NICKNAME) ?: return finish()

        setupUI()
        setupRemoteControl(serverUrl, roomId, nickname)
    }

    private fun setupUI() {
        // 触摸覆盖层监听
        binding.touchOverlay.listener = object : TouchOverlayView.Listener {
            override fun onMouseMove(xRatio: Float, yRatio: Float) {
                remoteControlManager?.sendMouseMove(xRatio, yRatio)
            }

            override fun onMouseDown(xRatio: Float, yRatio: Float, button: Int) {
                remoteControlManager?.sendMouseDown(xRatio, yRatio, button)
            }

            override fun onMouseUp(xRatio: Float, yRatio: Float, button: Int) {
                remoteControlManager?.sendMouseUp(xRatio, yRatio, button)
            }

            override fun onMouseDoubleClick(xRatio: Float, yRatio: Float, button: Int) {
                remoteControlManager?.sendMouseDoubleClick(xRatio, yRatio, button)
            }

            override fun onMouseWheel(xRatio: Float, yRatio: Float, deltaY: Float) {
                remoteControlManager?.sendMouseWheel(xRatio, yRatio, deltaY)
            }

            override fun onToolbarToggle() {
                toggleToolbar()
            }
        }

        // 断开连接按钮
        binding.btnDisconnect.setOnClickListener {
            finish()
        }

        // 键盘按钮（TODO: 实现虚拟键盘）
        binding.btnKeyboard.setOnClickListener {
            Toast.makeText(this, "虚拟键盘功能开发中", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupRemoteControl(serverUrl: String, roomId: String, nickname: String) {
        remoteControlManager = RemoteControlManager(
            context = this,
            serverUrl = serverUrl,
            roomId = roomId,
            nickname = nickname,
            listener = remoteControlListener
        )

        // 初始化 SurfaceViewRenderer
        val eglContext = remoteControlManager?.getEglContext()
        // 注意：需要在 WebRTC 初始化后获取 EglContext

        // 开始连接
        remoteControlManager?.connect()
    }

    private val remoteControlListener = object : RemoteControlManager.Listener {
        override fun onConnectionStateChanged(state: RemoteControlManager.ConnectionState) {
            runOnUiThread {
                updateConnectionState(state)
            }
        }

        override fun onVideoTrackReceived(track: VideoTrack) {
            runOnUiThread {
                setupVideoRenderer(track)
            }
        }

        override fun onDataChannelReady() {
            runOnUiThread {
                Toast.makeText(this@RemoteActivity, "控制通道已就绪", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onChatMessage(sender: String, text: String) {
            runOnUiThread {
                Toast.makeText(this@RemoteActivity, "$sender: $text", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onError(error: String) {
            runOnUiThread {
                Toast.makeText(this@RemoteActivity, "错误: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupVideoRenderer(track: VideoTrack) {
        // 释放之前的轨道
        currentVideoTrack?.removeSink(binding.remoteVideoView)

        // 初始化 SurfaceViewRenderer
        val eglContext = remoteControlManager?.getEglContext()
        if (eglContext != null) {
            try {
                binding.remoteVideoView.init(eglContext, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {
                        runOnUiThread {
                            // 隐藏等待覆盖层
                            binding.layoutWaiting.visibility = View.GONE
                            binding.layoutToolbar.visibility = View.VISIBLE
                            binding.tvHint.visibility = View.VISIBLE

                            // 延迟隐藏提示
                            binding.tvHint.postDelayed({
                                binding.tvHint.visibility = View.GONE
                            }, 5000)
                        }
                    }

                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                        runOnUiThread {
                            // 更新触摸覆盖层的视频尺寸
                            binding.touchOverlay.setVideoSize(width, height)
                        }
                    }
                })
                binding.remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                binding.remoteVideoView.setEnableHardwareScaler(true)
            } catch (e: Exception) {
                // 可能已经初始化过
            }
        }

        // 添加视频轨道
        track.addSink(binding.remoteVideoView)
        currentVideoTrack = track
    }

    private fun updateConnectionState(state: RemoteControlManager.ConnectionState) {
        when (state) {
            RemoteControlManager.ConnectionState.DISCONNECTED -> {
                binding.tvWaitingStatus.text = "已断开连接"
                binding.viewConnectionIndicator.setBackgroundResource(android.R.color.holo_red_light)
                binding.tvConnectionStatus.text = "已断开"
            }
            RemoteControlManager.ConnectionState.CONNECTING -> {
                binding.layoutWaiting.visibility = View.VISIBLE
                binding.tvWaitingStatus.text = "连接中..."
                binding.viewConnectionIndicator.setBackgroundResource(android.R.color.holo_orange_light)
                binding.tvConnectionStatus.text = "连接中"
            }
            RemoteControlManager.ConnectionState.CONNECTED -> {
                binding.tvWaitingStatus.text = "等待视频流..."
                binding.viewConnectionIndicator.setBackgroundResource(android.R.color.holo_green_light)
                binding.tvConnectionStatus.text = "已连接"
            }
            RemoteControlManager.ConnectionState.VIDEO_READY -> {
                binding.viewConnectionIndicator.setBackgroundResource(android.R.color.holo_green_light)
                binding.tvConnectionStatus.text = "视频就绪"
            }
        }
    }

    private fun toggleToolbar() {
        if (binding.layoutToolbar.visibility == View.VISIBLE) {
            binding.layoutToolbar.visibility = View.GONE
        } else {
            binding.layoutToolbar.visibility = View.VISIBLE
            // 5秒后自动隐藏
            binding.layoutToolbar.postDelayed({
                binding.layoutToolbar.visibility = View.GONE
            }, 5000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 释放视频轨道
        currentVideoTrack?.removeSink(binding.remoteVideoView)
        binding.remoteVideoView.release()

        // 断开连接
        remoteControlManager?.disconnect()
        remoteControlManager = null
    }
}

package com.example.remotecontrol.ui.main.fragment

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.remotecontrol.databinding.FragmentStatusBinding
import com.example.remotecontrol.manager.ConnectionManager
import org.webrtc.VideoTrack

/**
 * 状态监控 Fragment
 */
class StatusFragment : Fragment(), ConnectionManager.ConnectionStateListener {
    
    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatusBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        ConnectionManager.addStateListener(this)
        updateUI(ConnectionManager.statusInfo)
    }
    
    private fun updateUI(info: ConnectionManager.StatusInfo) {
        activity?.runOnUiThread {
            binding.tvWsStatus.text = info.wsStatus
            binding.tvDcStatus.text = info.dcStatus
            binding.tvIceStatus.text = info.iceStatus
            binding.tvOverallStatus.text = info.overallStatus
            
            // 更新指示器颜色
            updateIndicator(binding.indicatorWs, getStatusColor(info.wsStatus))
            updateIndicator(binding.indicatorDc, getStatusColor(info.dcStatus))
            updateIndicator(binding.indicatorIce, getStatusColor(info.iceStatus))
            
            // 总体状态颜色
            val overallColor = when {
                info.overallStatus.contains("就绪") || info.overallStatus.contains("已连接") -> 
                    android.graphics.Color.parseColor("#22C55E")
                info.overallStatus.contains("失败") || info.overallStatus.contains("错误") -> 
                    android.graphics.Color.parseColor("#EF4444")
                else -> 
                    android.graphics.Color.parseColor("#F59E0B")
            }
            binding.tvOverallStatus.setTextColor(overallColor)
            
            // 连接信息
            if (info.roomId.isNotEmpty()) {
                binding.tvConnectionInfo.text = """
                    房间: ${info.roomId}
                    昵称: ${info.nickname}
                """.trimIndent()
            } else {
                binding.tvConnectionInfo.text = "未连接"
            }
        }
    }
    
    private fun getStatusColor(status: String): Int {
        return when {
            status.contains("已连接") || status.contains("CONNECTED") || status.contains("COMPLETED") ->
                android.graphics.Color.parseColor("#22C55E")
            status.contains("失败") || status.contains("FAILED") || status.contains("错误") ->
                android.graphics.Color.parseColor("#EF4444")
            status.contains("未连接") || status.contains("未初始化") ->
                android.graphics.Color.parseColor("#6B7280")
            else ->
                android.graphics.Color.parseColor("#F59E0B")
        }
    }
    
    private fun updateIndicator(view: View, color: Int) {
        val drawable = view.background as? GradientDrawable ?: GradientDrawable()
        drawable.setColor(color)
        drawable.shape = GradientDrawable.OVAL
        view.background = drawable
    }
    
    override fun onStateChanged(state: ConnectionManager.State, info: ConnectionManager.StatusInfo) {
        updateUI(info)
    }
    
    override fun onVideoTrackReceived(track: VideoTrack) {
        // 不处理
    }
    
    override fun onError(error: String) {
        // 错误在 ConfigFragment 处理
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        ConnectionManager.removeStateListener(this)
        _binding = null
    }
}

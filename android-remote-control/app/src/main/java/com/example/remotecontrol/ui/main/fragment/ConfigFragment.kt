package com.example.remotecontrol.ui.main.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.remotecontrol.databinding.FragmentConfigBinding
import com.example.remotecontrol.manager.ConfigManager
import com.example.remotecontrol.manager.ConnectionManager
import com.example.remotecontrol.manager.LogManager
import org.webrtc.VideoTrack

/**
 * 配置 Fragment
 */
class ConfigFragment : Fragment(), ConnectionManager.ConnectionStateListener {
    
    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var configManager: ConfigManager
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        configManager = ConfigManager(requireContext())
        loadConfig()
        setupListeners()
        
        ConnectionManager.addStateListener(this)
        updateUIState(ConnectionManager.currentState)
    }
    
    private fun loadConfig() {
        binding.etServerUrl.setText(configManager.signalUrl)
        binding.etRoomId.setText(configManager.roomId)
        binding.etNickname.setText(configManager.nickname)
        binding.etStunUrls.setText(configManager.stunUrls)
        binding.etTurnUrls.setText(configManager.turnUrls)
        binding.etTurnUser.setText(configManager.turnUser)
        binding.etTurnPass.setText(configManager.turnPass)
    }
    
    private fun saveConfig() {
        configManager.signalUrl = binding.etServerUrl.text?.toString()?.trim() ?: ""
        configManager.roomId = binding.etRoomId.text?.toString()?.trim() ?: ""
        configManager.nickname = binding.etNickname.text?.toString()?.trim() ?: ""
        configManager.stunUrls = binding.etStunUrls.text?.toString()?.trim() ?: ""
        configManager.turnUrls = binding.etTurnUrls.text?.toString()?.trim() ?: ""
        configManager.turnUser = binding.etTurnUser.text?.toString()?.trim() ?: ""
        configManager.turnPass = binding.etTurnPass.text?.toString() ?: ""
        
        LogManager.i("配置已保存")
    }
    
    private fun setupListeners() {
        binding.btnSaveConfig.setOnClickListener {
            saveConfig()
            Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show()
        }
        
        binding.btnConnect.setOnClickListener {
            saveConfig()  // 连接前自动保存
            ConnectionManager.connect(requireContext(), configManager)
        }
        
        binding.btnDisconnect.setOnClickListener {
            ConnectionManager.disconnect()
        }
    }
    
    private fun updateUIState(state: ConnectionManager.State) {
        activity?.runOnUiThread {
            val isDisconnected = state == ConnectionManager.State.DISCONNECTED
            val isConnecting = state == ConnectionManager.State.CONNECTING
            
            binding.btnConnect.isEnabled = isDisconnected
            binding.btnDisconnect.isEnabled = !isDisconnected
            
            if (isConnecting) {
                binding.btnConnect.text = "连接中..."
            } else {
                binding.btnConnect.text = "启动连接"
            }
            
            // 配置输入框在连接时禁用
            val inputsEnabled = isDisconnected
            binding.etServerUrl.isEnabled = inputsEnabled
            binding.etRoomId.isEnabled = inputsEnabled
            binding.etNickname.isEnabled = inputsEnabled
            binding.etStunUrls.isEnabled = inputsEnabled
            binding.etTurnUrls.isEnabled = inputsEnabled
            binding.etTurnUser.isEnabled = inputsEnabled
            binding.etTurnPass.isEnabled = inputsEnabled
        }
    }
    
    override fun onStateChanged(state: ConnectionManager.State, info: ConnectionManager.StatusInfo) {
        updateUIState(state)
    }
    
    override fun onVideoTrackReceived(track: VideoTrack) {
        // 不处理
    }
    
    override fun onError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), "错误: $error", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        ConnectionManager.removeStateListener(this)
        _binding = null
    }
}

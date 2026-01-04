package com.example.remotecontrol.ui.connect

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.example.remotecontrol.R
import com.example.remotecontrol.databinding.ActivityConnectBinding
import com.example.remotecontrol.ui.remote.RemoteActivity

/**
 * 连接配置界面
 */
class ConnectActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "remote_control_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_NICKNAME = "nickname"
    }

    private lateinit var binding: ActivityConnectBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadSavedConfig()

        binding.btnConnect.setOnClickListener {
            validateAndConnect()
        }
    }

    private fun loadSavedConfig() {
        val serverUrl = prefs.getString(KEY_SERVER_URL, "ws://192.168.1.100:8080/ws")
        val roomId = prefs.getString(KEY_ROOM_ID, "room123")
        val nickname = prefs.getString(KEY_NICKNAME, "Android")

        binding.etServerUrl.setText(serverUrl)
        binding.etRoomId.setText(roomId)
        binding.etNickname.setText(nickname)
    }

    private fun saveConfig(serverUrl: String, roomId: String, nickname: String) {
        prefs.edit {
            putString(KEY_SERVER_URL, serverUrl)
            putString(KEY_ROOM_ID, roomId)
            putString(KEY_NICKNAME, nickname)
        }
    }

    private fun validateAndConnect() {
        val serverUrl = binding.etServerUrl.text?.toString()?.trim() ?: ""
        val roomId = binding.etRoomId.text?.toString()?.trim() ?: ""
        val nickname = binding.etNickname.text?.toString()?.trim() ?: ""

        // 验证输入
        when {
            serverUrl.isEmpty() -> {
                binding.tilServerUrl.error = getString(R.string.error_empty_server)
                return
            }
            roomId.isEmpty() -> {
                binding.tilRoomId.error = getString(R.string.error_empty_room)
                return
            }
            nickname.isEmpty() -> {
                binding.tilNickname.error = getString(R.string.error_empty_nickname)
                return
            }
        }

        // 清除错误
        binding.tilServerUrl.error = null
        binding.tilRoomId.error = null
        binding.tilNickname.error = null

        // 保存配置
        saveConfig(serverUrl, roomId, nickname)

        // 显示连接状态
        showConnecting()

        // 启动远程控制界面
        val intent = Intent(this, RemoteActivity::class.java).apply {
            putExtra(RemoteActivity.EXTRA_SERVER_URL, serverUrl)
            putExtra(RemoteActivity.EXTRA_ROOM_ID, roomId)
            putExtra(RemoteActivity.EXTRA_NICKNAME, nickname)
        }
        startActivity(intent)

        // 重置状态
        hideConnecting()
    }

    private fun showConnecting() {
        binding.btnConnect.isEnabled = false
        binding.btnConnect.text = getString(R.string.connecting)
        binding.layoutStatus.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.connecting)
    }

    private fun hideConnecting() {
        binding.btnConnect.isEnabled = true
        binding.btnConnect.text = getString(R.string.btn_connect)
        binding.layoutStatus.visibility = View.GONE
    }
}

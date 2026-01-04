package com.example.remotecontrol.manager

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理器
 */
class ConfigManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "remote_control_config"
        
        private const val KEY_SIGNAL_URL = "signal_url"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_STUN_URLS = "stun_urls"
        private const val KEY_TURN_URLS = "turn_urls"
        private const val KEY_TURN_USER = "turn_user"
        private const val KEY_TURN_PASS = "turn_pass"
        
        // 默认值 - 与 JavaFX Agent 保持一致
        private const val DEFAULT_SIGNAL_URL = "ws://localhost:8080/ws"
        private const val DEFAULT_ROOM_ID = "demo-room"
        private const val DEFAULT_NICKNAME = "Android"
        private const val DEFAULT_STUN_URLS = "stun:43.139.50.108:3478"
        private const val DEFAULT_TURN_URLS = "turn:43.139.50.108:3478?transport=udp,turn:43.139.50.108:3478?transport=tcp"
        private const val DEFAULT_TURN_USER = "admin"
        private const val DEFAULT_TURN_PASS = "123456"
    }

    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    var signalUrl: String
        get() = prefs.getString(KEY_SIGNAL_URL, DEFAULT_SIGNAL_URL) ?: DEFAULT_SIGNAL_URL
        set(value) = prefs.edit().putString(KEY_SIGNAL_URL, value).apply()
    
    var roomId: String
        get() = prefs.getString(KEY_ROOM_ID, DEFAULT_ROOM_ID) ?: DEFAULT_ROOM_ID
        set(value) = prefs.edit().putString(KEY_ROOM_ID, value).apply()
    
    var nickname: String
        get() = prefs.getString(KEY_NICKNAME, DEFAULT_NICKNAME) ?: DEFAULT_NICKNAME
        set(value) = prefs.edit().putString(KEY_NICKNAME, value).apply()
    
    var stunUrls: String
        get() = prefs.getString(KEY_STUN_URLS, DEFAULT_STUN_URLS) ?: DEFAULT_STUN_URLS
        set(value) = prefs.edit().putString(KEY_STUN_URLS, value).apply()
    
    var turnUrls: String
        get() = prefs.getString(KEY_TURN_URLS, DEFAULT_TURN_URLS) ?: DEFAULT_TURN_URLS
        set(value) = prefs.edit().putString(KEY_TURN_URLS, value).apply()
    
    var turnUser: String
        get() = prefs.getString(KEY_TURN_USER, DEFAULT_TURN_USER) ?: DEFAULT_TURN_USER
        set(value) = prefs.edit().putString(KEY_TURN_USER, value).apply()
    
    var turnPass: String
        get() = prefs.getString(KEY_TURN_PASS, DEFAULT_TURN_PASS) ?: DEFAULT_TURN_PASS
        set(value) = prefs.edit().putString(KEY_TURN_PASS, value).apply()
    
    /**
     * 获取 ICE 服务器列表
     */
    fun getIceServers(): List<IceServerConfig> {
        val servers = mutableListOf<IceServerConfig>()
        
        // STUN 服务器
        stunUrls.split(",").filter { it.isNotBlank() }.forEach { url ->
            servers.add(IceServerConfig(url.trim()))
        }
        
        // TURN 服务器
        turnUrls.split(",").filter { it.isNotBlank() }.forEach { url ->
            servers.add(IceServerConfig(url.trim(), turnUser, turnPass))
        }
        
        return servers
    }
    
    data class IceServerConfig(
        val url: String,
        val username: String = "",
        val password: String = ""
    )
}

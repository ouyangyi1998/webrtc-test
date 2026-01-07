package com.example.remotecontrol.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * 网络状态监听器
 * 监听网络变化，在 WiFi 切换等场景下触发快速重连
 */
class NetworkMonitor(private val context: Context) {
    
    companion object {
        private const val TAG = "NetworkMonitor"
    }
    
    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    
    private var isRegistered = false
    private var lastNetworkId: String? = null
    
    /**
     * 网络变化回调接口
     */
    interface NetworkChangeListener {
        fun onNetworkAvailable()
        fun onNetworkLost()
    }
    
    private var listener: NetworkChangeListener? = null
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var networkLostHandler: android.os.Handler? = null
        private var pendingNetworkLost: Runnable? = null
        
        override fun onAvailable(network: Network) {
            val networkId = network.toString()
            LogManager.i("[$TAG] 网络可用: $networkId")
            
            // 取消待发送的网络丢失通知
            pendingNetworkLost?.let {
                networkLostHandler?.removeCallbacks(it)
                pendingNetworkLost = null
                LogManager.d("[$TAG] 取消待发送的网络丢失通知（网络快速恢复）")
            }
            
            // 网络恢复时始终触发重连（无论是否是同一网络）
            // 因为 WebSocket 可能已经断开，需要确保连接状态
            if (lastNetworkId != null) {
                if (lastNetworkId != networkId) {
                    LogManager.i("[$TAG] 检测到网络切换: $lastNetworkId -> $networkId")
                } else {
                    LogManager.i("[$TAG] 网络恢复（同一网络）: $networkId")
                }
                listener?.onNetworkAvailable()
            }
            lastNetworkId = networkId
        }
        
        override fun onLost(network: Network) {
            LogManager.w("[$TAG] 网络丢失: $network")
            
            // 延迟500ms再触发网络丢失，避免WiFi切换时短暂断连误触发
            if (networkLostHandler == null) {
                networkLostHandler = android.os.Handler(android.os.Looper.getMainLooper())
            }
            
            pendingNetworkLost?.let { networkLostHandler?.removeCallbacks(it) }
            pendingNetworkLost = Runnable {
                LogManager.w("[$TAG] 网络丢失确认（延迟后）")
                listener?.onNetworkLost()
                pendingNetworkLost = null
            }
            networkLostHandler?.postDelayed(pendingNetworkLost!!, 500)
        }
        
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // 可选：监控网络能力变化（如从 WiFi 切换到 4G）
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            LogManager.d("[$TAG] 网络能力变化: internet=$hasInternet, validated=$hasValidated")
        }
    }
    
    /**
     * 注册网络监听
     */
    fun register(listener: NetworkChangeListener) {
        if (isRegistered) {
            LogManager.w("[$TAG] NetworkMonitor 已注册")
            return
        }
        
        this.listener = listener
        
        try {
            // 使用 registerDefaultNetworkCallback 监听默认网络变化
            // 这样可以在 WiFi 切换时立即感知到
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            isRegistered = true
            LogManager.i("[$TAG] NetworkMonitor 注册成功")
            
            // 记录当前网络 ID
            connectivityManager.activeNetwork?.let {
                lastNetworkId = it.toString()
                LogManager.i("[$TAG] 当前网络: $lastNetworkId")
            }
        } catch (e: Exception) {
            LogManager.e("[$TAG] NetworkMonitor 注册失败: ${e.message}")
        }
    }
    
    /**
     * 注销网络监听
     */
    fun unregister() {
        if (!isRegistered) {
            return
        }
        
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
            listener = null
            lastNetworkId = null
            LogManager.i("[$TAG] NetworkMonitor 已注销")
        } catch (e: Exception) {
            LogManager.e("[$TAG] NetworkMonitor 注销失败: ${e.message}")
        }
    }
    
    /**
     * 检查当前是否有网络连接
     */
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

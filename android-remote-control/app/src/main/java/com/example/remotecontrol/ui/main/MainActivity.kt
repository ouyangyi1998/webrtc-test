package com.example.remotecontrol.ui.main

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.remotecontrol.R
import com.example.remotecontrol.databinding.ActivityMainBinding
import com.example.remotecontrol.manager.ConnectionManager
import com.example.remotecontrol.manager.LogManager
import com.example.remotecontrol.ui.main.fragment.ChatFragment
import com.example.remotecontrol.ui.main.fragment.ConfigFragment
import com.example.remotecontrol.ui.main.fragment.LogFragment
import com.example.remotecontrol.ui.main.fragment.StatusFragment

/**
 * 主界面 - 使用 BottomNavigationView 切换 Fragment
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    private val configFragment = ConfigFragment()
    private val logFragment = LogFragment()
    private val statusFragment = StatusFragment()
    private val chatFragment = ChatFragment()
    
    private var activeFragment: Fragment = configFragment
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        LogManager.i("应用已启动")
        
        setupFragments()
        setupBottomNavigation()
        
        // 检查无障碍服务权限
        checkAccessibilityPermission()
        // 检查悬浮窗权限（新增）
        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("为了显示触控波纹反馈，请授予应用\"显示在其他应用上层\"的权限。")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton("稍后") { dialog, _ -> 
                        dialog.dismiss() 
                    }
                    .show()
            }
        }
    }
    
    private fun checkAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("需要无障碍权限")
                .setMessage("远程控制功能需要启用无障碍服务。\n\n请在设置中找到\"远程控制服务\"并启用。")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("稍后") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${com.example.remotecontrol.service.RemoteControlService::class.java.canonicalName}"
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceName)
    }
    
    private fun setupFragments() {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, chatFragment, "chat").hide(chatFragment)
            .add(R.id.fragmentContainer, statusFragment, "status").hide(statusFragment)
            .add(R.id.fragmentContainer, logFragment, "log").hide(logFragment)
            .add(R.id.fragmentContainer, configFragment, "config")
            .commit()
    }
    
    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_config -> configFragment
                R.id.nav_log -> logFragment
                R.id.nav_status -> statusFragment
                R.id.nav_chat -> chatFragment
                else -> return@setOnItemSelectedListener false
            }
            
            supportFragmentManager.beginTransaction()
                .hide(activeFragment)
                .show(fragment)
                .commit()
            
            activeFragment = fragment
            true
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        ConnectionManager.disconnect()
        LogManager.i("应用已关闭")
    }
}

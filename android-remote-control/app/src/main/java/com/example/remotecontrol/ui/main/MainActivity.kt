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

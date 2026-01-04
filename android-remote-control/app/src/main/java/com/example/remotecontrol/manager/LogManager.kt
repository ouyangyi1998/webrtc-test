package com.example.remotecontrol.manager

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 日志管理器（单例）
 */
object LogManager {
    private const val TAG = "LogManager"
    private const val MAX_LOGS = 1000
    
    private val logs = CopyOnWriteArrayList<LogEntry>()
    private val listeners = mutableListOf<LogListener>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    data class LogEntry(
        val timestamp: Long,
        val message: String,
        val level: Level = Level.INFO
    ) {
        enum class Level { DEBUG, INFO, WARN, ERROR }
        
        fun formatted(): String {
            val time = dateFormat.format(Date(timestamp))
            val prefix = when (level) {
                Level.DEBUG -> "[D]"
                Level.INFO -> "[I]"
                Level.WARN -> "[W]"
                Level.ERROR -> "[E]"
            }
            return "[$time] $prefix $message"
        }
    }
    
    interface LogListener {
        fun onLogAdded(entry: LogEntry)
        fun onLogsCleared()
    }
    
    fun addListener(listener: LogListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: LogListener) {
        listeners.remove(listener)
    }
    
    fun log(message: String, level: LogEntry.Level = LogEntry.Level.INFO) {
        val entry = LogEntry(System.currentTimeMillis(), message, level)
        logs.add(entry)
        
        // 保持日志数量限制
        while (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
        
        // 通知监听器
        listeners.forEach { it.onLogAdded(entry) }
        
        // 同时输出到 Logcat
        when (level) {
            LogEntry.Level.DEBUG -> android.util.Log.d(TAG, message)
            LogEntry.Level.INFO -> android.util.Log.i(TAG, message)
            LogEntry.Level.WARN -> android.util.Log.w(TAG, message)
            LogEntry.Level.ERROR -> android.util.Log.e(TAG, message)
        }
    }
    
    fun d(message: String) = log(message, LogEntry.Level.DEBUG)
    fun i(message: String) = log(message, LogEntry.Level.INFO)
    fun w(message: String) = log(message, LogEntry.Level.WARN)
    fun e(message: String) = log(message, LogEntry.Level.ERROR)
    
    fun getLogs(): List<LogEntry> = logs.toList()
    
    fun clear() {
        logs.clear()
        listeners.forEach { it.onLogsCleared() }
    }
    
    fun export(context: Context): File? {
        return try {
            val exportDir = File(context.getExternalFilesDir(null), "logs")
            if (!exportDir.exists()) exportDir.mkdirs()
            
            val fileName = "log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
            val file = File(exportDir, fileName)
            
            file.writeText(logs.joinToString("\n") { it.formatted() })
            i("日志已导出到: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            e("导出日志失败: ${e.message}")
            null
        }
    }
}

package com.example.remotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.remotecontrol.manager.ConnectionManager
import com.example.remotecontrol.manager.LogManager

/**
 * 远程控制 AccessibilityService
 * 接收来自 Web 端的控制消息，执行真实的鼠标/键盘模拟
 */
class RemoteControlService : AccessibilityService(), ConnectionManager.ControlListener {
    
    companion object {
        private const val TAG = "RemoteControlService"
        
        @Volatile
        var instance: RemoteControlService? = null
            private set
            
        // 共享屏幕尺寸，供 WebRTCManager 使用
        var captureWidth = 1080
            private set
        var captureHeight = 1920
            private set
    }
    
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var wakeLock: PowerManager.WakeLock? = null
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        // 获取屏幕尺寸
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        
        // 更新共享的捕获尺寸
        captureWidth = screenWidth
        captureHeight = screenHeight
        
        LogManager.i("RemoteControlService 已启动 (屏幕: ${screenWidth}x${screenHeight})")
        
        // 注册控制监听器
        ConnectionManager.addControlListener(this)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        ConnectionManager.removeControlListener(this)
        LogManager.i("RemoteControlService 已停止")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理无障碍事件
    }
    
    override fun onInterrupt() {
        // 服务中断
    }
    
    // ========== 唤醒屏幕 ==========
    
    /**
     * 唤醒屏幕
     */
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                // 屏幕关闭，唤醒它
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or 
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                    "RemoteControl:WakeLock"
                )
                wl.acquire(3000) // 持续3秒
                LogManager.i("屏幕已唤醒")
            }
        } catch (e: Exception) {
            LogManager.e("唤醒屏幕失败: ${e.message}")
        }
    }
    
    // ========== 控制事件处理 ==========
    
    // 拖动状态
    private var isDragging = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var lastDragX = 0
    private var lastDragY = 0
    
    override fun onMouseControl(action: String, xRatio: Float, yRatio: Float, button: Int, deltaY: Float) {
        // 收到控制事件时唤醒屏幕
        if (action != "move") {
            wakeScreen()
        }
        
        val x = (xRatio * screenWidth).toInt().coerceIn(0, screenWidth - 1)
        val y = (yRatio * screenHeight).toInt().coerceIn(0, screenHeight - 1)
        
        when (action) {
            "move" -> {
                // 如果正在拖动，更新位置（用于后续 mouseup 时执行滑动）
                if (isDragging) {
                    lastDragX = x
                    lastDragY = y
                }
            }
            "mousedown" -> {
                when (button) {
                    0 -> {
                        // 左键按下 - 开始拖动追踪
                        isDragging = true
                        dragStartX = x
                        dragStartY = y
                        lastDragX = x
                        lastDragY = y
                    }
                    2 -> performLongPress(x, y)   // 右键 -> 长按
                }
            }
            "mouseup" -> {
                when (button) {
                    0 -> {
                        if (isDragging) {
                            val dx = lastDragX - dragStartX
                            val dy = lastDragY - dragStartY
                            val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
                            
                            if (distance < 20) {
                                // 移动距离很小，视为点击
                                performClick(dragStartX, dragStartY)
                            } else {
                                // 移动距离较大，执行滑动
                                performSwipe(dragStartX, dragStartY, lastDragX, lastDragY, 200)
                            }
                            isDragging = false
                        }
                    }
                }
            }
            "click" -> {
                performClick(x, y)
            }
            "dblclick" -> {
                performDoubleClick(x, y)
            }
            "wheel" -> {
                // 滚轮 - 模拟滑动（Web 端 deltaY 正值向下）
                performScroll(x, y, -deltaY)
            }
        }
    }
    
    override fun onKeyboardControl(
        type: String, key: String, code: String,
        altKey: Boolean, ctrlKey: Boolean, metaKey: Boolean, shiftKey: Boolean
    ) {
        if (type != "keydown") return
        
        LogManager.i("键盘输入: key=$key, code=$code, ctrl=$ctrlKey, meta=$metaKey")
        
        // 1. 快捷键处理
        when {
            // Escape / Android Back
            key == "Escape" || code == "Escape" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                LogManager.i("执行返回操作")
                return
            }
            // Backspace - 使用 AccessibilityNodeInfo 删除
            key == "Backspace" || code == "Backspace" -> {
                performBackspace()
                return
            }
            // Enter
            key == "Enter" || code == "Enter" -> {
                performEnter()
                return
            }
            // Home (Meta+H or Home key)
            (metaKey && key.lowercase() == "h") || code == "Home" -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                LogManager.i("执行主页操作")
                return
            }
            // Recent Apps (Meta+Tab)
            metaKey && key == "Tab" -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                LogManager.i("执行最近应用操作")
                return
            }
            // 方向键 - 暂不支持
            code.startsWith("Arrow") -> {
                LogManager.d("方向键暂不支持")
                return
            }
        }
        
        // 2. 普通字符输入（通过剪贴板粘贴方式）
        if (key.length == 1 && !ctrlKey && !altKey && !metaKey) {
            performTextInput(key)
        }
    }
    
    /**
     * 通过 ACTION_SET_TEXT 输入文本
     */
    private fun performTextInput(text: String) {
        try {
            val focusedNode = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode == null) {
                LogManager.w("未找到输入焦点，无法输入文本")
                return
            }
            
            // 刷新节点获取最新状态
            focusedNode.refresh()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // 获取当前可编辑文本（排除 hint）
                val currentText = getEditableText(focusedNode)
                val newText = currentText + text
                
                val arguments = android.os.Bundle()
                arguments.putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
                )
                focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                LogManager.d("输入文本: '$text', 当前: '$currentText' -> '$newText'")
            }
            
            focusedNode.recycle()
        } catch (e: Exception) {
            LogManager.e("输入文本失败: ${e.message}")
        }
    }
    
    /**
     * 获取可编辑文本（排除 hint 提示文字）
     */
    private fun getEditableText(node: android.view.accessibility.AccessibilityNodeInfo): String {
        // 优先使用 text（如果不是 hint）
        val text = node.text?.toString()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()
        } else {
            null
        }
        
        // 如果 text 等于 hint，说明输入框为空（显示的是 hint）
        return if (text != null && text != hint) {
            text
        } else {
            ""
        }
    }
    
    /**
     * 执行退格删除
     */
    private fun performBackspace() {
        try {
            val focusedNode = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode == null) {
                LogManager.w("未找到输入焦点")
                return
            }
            
            // 刷新节点获取最新状态
            focusedNode.refresh()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val currentText = getEditableText(focusedNode)
                if (currentText.isNotEmpty()) {
                    val newText = currentText.dropLast(1)
                    val arguments = android.os.Bundle()
                    arguments.putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        newText
                    )
                    focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    LogManager.d("删除字符: '$currentText' -> '$newText'")
                }
            }
            
            focusedNode.recycle()
        } catch (e: Exception) {
            LogManager.e("删除失败: ${e.message}")
        }
    }
    
    /**
     * 执行回车
     */
    private fun performEnter() {
        // 尝试找到当前焦点的输入框并执行 ACTION_NEXT 或提交
        try {
            val focusedNode = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                // 尝试执行下一个焦点或提交动作
                val result = focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
                if (!result) {
                    // 如果没有下一个，尝试点击（可能是搜索按钮等）
                    focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                focusedNode.recycle()
                LogManager.d("执行回车")
            }
        } catch (e: Exception) {
            LogManager.e("回车失败: ${e.message}")
        }
    }
    
    // ========== 手势模拟 ==========
    
    /**
     * 模拟点击
     */
    private fun performClick(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            LogManager.w("手势模拟需要 Android 7.0+")
            return
        }
        
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "点击完成: ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "点击取消: ($x, $y)")
            }
        }, null)
    }
    
    /**
     * 模拟双击
     */
    private fun performDoubleClick(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        
        // 第一次点击
        val gesture1 = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        
        dispatchGesture(gesture1, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // 延迟后执行第二次点击
                android.os.Handler(mainLooper).postDelayed({
                    val gesture2 = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                        .build()
                    dispatchGesture(gesture2, null, null)
                }, 100)
            }
        }, null)
    }
    
    /**
     * 模拟滚动
     */
    private fun performScroll(x: Int, y: Int, deltaY: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        
        val scrollAmount = (deltaY * 2).toInt().coerceIn(-500, 500)
        val endY = (y + scrollAmount).coerceIn(0, screenHeight - 1)
        
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        path.lineTo(x.toFloat(), endY.toFloat())
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "滚动完成: ($x, $y) -> ($x, $endY)")
            }
        }, null)
    }
    
    /**
     * 模拟长按
     */
    fun performLongPress(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))  // 1秒长按
            .build()
        
        dispatchGesture(gesture, null, null)
    }
    
    /**
     * 模拟滑动
     */
    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        
        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        path.lineTo(endX.toFloat(), endY.toFloat())
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        dispatchGesture(gesture, null, null)
    }
}

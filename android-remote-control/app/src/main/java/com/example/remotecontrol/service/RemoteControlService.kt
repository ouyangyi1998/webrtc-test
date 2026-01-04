package com.example.remotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
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
    }
    
    private var screenWidth = 1080
    private var screenHeight = 1920
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        // 获取屏幕尺寸
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        
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
    
    // ========== 控制事件处理 ==========
    
    override fun onMouseControl(action: String, xRatio: Float, yRatio: Float, button: Int, deltaY: Float) {
        val x = (xRatio * screenWidth).toInt().coerceIn(0, screenWidth - 1)
        val y = (yRatio * screenHeight).toInt().coerceIn(0, screenHeight - 1)
        
        when (action) {
            "move" -> {
                // 鼠标移动暂不处理（Android 没有光标概念）
            }
            "mousedown" -> {
                // 只在 mousedown 时执行点击，不等 mouseup
                when (button) {
                    0 -> performClick(x, y)       // 左键点击
                    2 -> performLongPress(x, y)   // 右键 -> 长按
                }
            }
            "mouseup" -> {
                // mouseup 不处理，避免重复点击
            }
            "click" -> {
                // 完整点击事件
                performClick(x, y)
            }
            "dblclick" -> {
                // 双击
                performDoubleClick(x, y)
            }
            "wheel" -> {
                // 滚轮 - 模拟滑动（注意：Web 端 deltaY 正值向下，需要反向模拟滑动）
                performScroll(x, y, -deltaY)
            }
        }
    }
    
    override fun onKeyboardControl(
        type: String, key: String, code: String,
        altKey: Boolean, ctrlKey: Boolean, metaKey: Boolean, shiftKey: Boolean
    ) {
        if (type == "keydown") {
            LogManager.i("键盘输入: key=$key, code=$code")
            // Android AccessibilityService 不支持直接模拟键盘输入
            // 对于文本输入，可以使用粘贴方式
            if (key.length == 1 && !ctrlKey && !altKey && !metaKey) {
                // 单字符输入（暂时日志记录）
                LogManager.d("文本输入: $key")
            }
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

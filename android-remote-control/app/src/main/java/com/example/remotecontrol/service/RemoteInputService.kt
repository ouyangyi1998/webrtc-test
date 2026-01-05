package com.example.remotecontrol.service

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.example.remotecontrol.manager.ConnectionManager
import com.example.remotecontrol.manager.LogManager

/**
 * 远程输入法服务
 * 接收来自 Web 端的键盘输入，通过 InputConnection 注入文本
 */
class RemoteInputService : InputMethodService() {
    
    companion object {
        private const val TAG = "RemoteInputService"
        
        @Volatile
        var instance: RemoteInputService? = null
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        LogManager.i("RemoteInputService 已创建")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        LogManager.i("RemoteInputService 已销毁")
    }
    
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        LogManager.d("onStartInput: restarting=$restarting")
    }
    
    override fun onFinishInput() {
        super.onFinishInput()
        LogManager.d("onFinishInput")
    }
    
    /**
     * 注入文本
     */
    fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        LogManager.d("commitText: $text")
    }
    
    /**
     * 模拟按键事件
     */
    fun sendKeyEvent(keyCode: Int, action: Int) {
        val ic = currentInputConnection ?: return
        val event = android.view.KeyEvent(action, keyCode)
        ic.sendKeyEvent(event)
        LogManager.d("sendKeyEvent: keyCode=$keyCode, action=$action")
    }
    
    /**
     * 删除字符
     */
    fun deleteBackward(count: Int = 1) {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(count, 0)
        LogManager.d("deleteBackward: $count")
    }
    
    /**
     * 发送 Enter 键
     */
    fun sendEnter() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        LogManager.d("sendEnter")
    }
    
    /**
     * 移动光标
     */
    fun moveCursor(direction: String) {
        val ic = currentInputConnection ?: return
        val keyCode = when (direction) {
            "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            else -> return
        }
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }
}

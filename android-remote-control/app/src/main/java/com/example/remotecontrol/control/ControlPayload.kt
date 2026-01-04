package com.example.remotecontrol.control

import com.google.gson.Gson

/**
 * 控制消息载荷
 */
object ControlPayload {
    private val gson = Gson()

    /**
     * 鼠标移动消息
     */
    fun mouseMove(xRatio: Float, yRatio: Float): String {
        return gson.toJson(mapOf(
            "kind" to "mouse",
            "action" to "move",
            "xRatio" to xRatio,
            "yRatio" to yRatio
        ))
    }

    /**
     * 鼠标按下消息
     */
    fun mouseDown(xRatio: Float, yRatio: Float, button: Int = 0): String {
        return gson.toJson(mapOf(
            "kind" to "mouse",
            "action" to "mousedown",
            "xRatio" to xRatio,
            "yRatio" to yRatio,
            "button" to button
        ))
    }

    /**
     * 鼠标释放消息
     */
    fun mouseUp(xRatio: Float, yRatio: Float, button: Int = 0): String {
        return gson.toJson(mapOf(
            "kind" to "mouse",
            "action" to "mouseup",
            "xRatio" to xRatio,
            "yRatio" to yRatio,
            "button" to button
        ))
    }

    /**
     * 鼠标双击消息
     */
    fun mouseDoubleClick(xRatio: Float, yRatio: Float, button: Int = 0): String {
        return gson.toJson(mapOf(
            "kind" to "mouse",
            "action" to "dblclick",
            "xRatio" to xRatio,
            "yRatio" to yRatio,
            "button" to button
        ))
    }

    /**
     * 鼠标滚轮消息
     */
    fun mouseWheel(xRatio: Float, yRatio: Float, deltaY: Float): String {
        return gson.toJson(mapOf(
            "kind" to "mouse",
            "action" to "wheel",
            "xRatio" to xRatio,
            "yRatio" to yRatio,
            "deltaY" to deltaY
        ))
    }

    /**
     * 键盘事件消息
     */
    fun keyboard(
        type: String,  // "keydown" or "keyup"
        key: String,
        code: String,
        altKey: Boolean = false,
        ctrlKey: Boolean = false,
        metaKey: Boolean = false,
        shiftKey: Boolean = false,
        repeat: Boolean = false
    ): String {
        return gson.toJson(mapOf(
            "kind" to "keyboard",
            "type" to type,
            "key" to key,
            "code" to code,
            "altKey" to altKey,
            "ctrlKey" to ctrlKey,
            "metaKey" to metaKey,
            "shiftKey" to shiftKey,
            "repeat" to repeat
        ))
    }

    /**
     * 聊天消息
     */
    fun chat(sender: String, text: String): String {
        return gson.toJson(mapOf(
            "kind" to "chat",
            "sender" to sender,
            "text" to text
        ))
    }

    /**
     * 创建鼠标控制消息的 Map（用于降级通过 WebSocket 发送）
     */
    fun mousePayloadMap(action: String, xRatio: Float, yRatio: Float, button: Int = 0, deltaY: Float = 0f): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "kind" to "mouse",
            "action" to action,
            "xRatio" to xRatio,
            "yRatio" to yRatio
        )
        if (action != "move") {
            map["button"] = button
        }
        if (action == "wheel") {
            map["deltaY"] = deltaY
        }
        return map
    }

    /**
     * 创建键盘控制消息的 Map
     */
    fun keyboardPayloadMap(
        type: String,
        key: String,
        code: String,
        modifiers: Map<String, Boolean> = emptyMap()
    ): Map<String, Any> {
        return mapOf(
            "kind" to "keyboard",
            "type" to type,
            "key" to key,
            "code" to code,
            "altKey" to (modifiers["alt"] ?: false),
            "ctrlKey" to (modifiers["ctrl"] ?: false),
            "metaKey" to (modifiers["meta"] ?: false),
            "shiftKey" to (modifiers["shift"] ?: false)
        )
    }
}

package com.example.remotecontrol.signaling

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * 信令消息基类
 */
data class SignalMessage(
    val type: String,
    val roomId: String? = null,
    val sender: String? = null,
    val data: JsonObject? = null
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): SignalMessage? {
            return try {
                gson.fromJson(json, SignalMessage::class.java)
            } catch (e: Exception) {
                null
            }
        }

        // 创建 join 消息
        fun join(roomId: String, sender: String): String {
            return gson.toJson(mapOf(
                "type" to "join",
                "roomId" to roomId,
                "sender" to sender
            ))
        }

        // 创建 leave 消息
        fun leave(roomId: String, sender: String): String {
            return gson.toJson(mapOf(
                "type" to "leave",
                "roomId" to roomId,
                "sender" to sender
            ))
        }

        // 创建 offer 消息
        fun offer(roomId: String, sender: String, sdpType: String, sdp: String): String {
            return gson.toJson(mapOf(
                "type" to "offer",
                "roomId" to roomId,
                "sender" to sender,
                "data" to mapOf(
                    "sdp" to mapOf(
                        "type" to sdpType,
                        "sdp" to sdp
                    )
                )
            ))
        }

        // 创建 answer 消息
        fun answer(roomId: String, sender: String, sdpType: String, sdp: String): String {
            return gson.toJson(mapOf(
                "type" to "answer",
                "roomId" to roomId,
                "sender" to sender,
                "data" to mapOf(
                    "sdp" to mapOf(
                        "type" to sdpType,
                        "sdp" to sdp
                    )
                )
            ))
        }

        // 创建 candidate 消息
        fun candidate(roomId: String, sender: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int): String {
            return gson.toJson(mapOf(
                "type" to "candidate",
                "roomId" to roomId,
                "sender" to sender,
                "data" to mapOf(
                    "candidate" to mapOf(
                        "candidate" to candidate,
                        "sdpMid" to sdpMid,
                        "sdpMLineIndex" to sdpMLineIndex
                    )
                )
            ))
        }

        // 创建 ping 消息
        fun ping(): String {
            return gson.toJson(mapOf("type" to "ping"))
        }

        // 创建控制消息（通过 WebSocket 降级）
        fun control(roomId: String, sender: String, payload: Map<String, Any>): String {
            return gson.toJson(mapOf(
                "type" to "control",
                "roomId" to roomId,
                "sender" to sender,
                "data" to payload
            ))
        }
    }

    // 获取 participants 数量（from join-ack）
    fun getParticipants(): Int {
        val element = data?.get("participants")
        return if (element != null && !element.isJsonNull) element.asInt else 1
    }

    // 获取 SDP 信息
    fun getSdp(): Pair<String, String>? {
        val sdpObj = data?.getAsJsonObject("sdp") ?: return null
        val typeEl = sdpObj.get("type")
        val sdpEl = sdpObj.get("sdp")
        if (typeEl == null || typeEl.isJsonNull || sdpEl == null || sdpEl.isJsonNull) return null
        return Pair(typeEl.asString, sdpEl.asString)
    }

    // 获取 ICE Candidate 信息
    fun getCandidate(): Triple<String, String?, Int>? {
        val candidateObj = data?.getAsJsonObject("candidate") ?: return null
        val candidateEl = candidateObj.get("candidate")
        if (candidateEl == null || candidateEl.isJsonNull) return null
        val candidate = candidateEl.asString
        val sdpMidEl = candidateObj.get("sdpMid")
        val sdpMid = if (sdpMidEl != null && !sdpMidEl.isJsonNull) sdpMidEl.asString else null
        val sdpMLineIndexEl = candidateObj.get("sdpMLineIndex")
        val sdpMLineIndex = if (sdpMLineIndexEl != null && !sdpMLineIndexEl.isJsonNull) sdpMLineIndexEl.asInt else 0
        return Triple(candidate, sdpMid, sdpMLineIndex)
    }

    // 获取错误消息
    fun getErrorMessage(): String {
        val msgEl = data?.get("message")
        return if (msgEl != null && !msgEl.isJsonNull) msgEl.asString else "Unknown error"
    }
}

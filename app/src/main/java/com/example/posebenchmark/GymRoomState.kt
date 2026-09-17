package com.example.posebenchmark

import org.json.JSONObject

/** Absolute RoomState snapshot from Fit-Ai-IoT v1. Count is uint32 on the wire. */
data class GymRoomState(
    val schemaVersion: Int,
    val deviceId: String,
    val roomId: String,
    val sessionId: String,
    val sequence: Long,
    val count: Long,
    val event: String,
    val timestamp: Long,
    val status: String,
    val confidence: Double?
)

object GymRoomStateParser {
    private val required = setOf("schemaVersion", "deviceId", "roomId", "sessionId",
        "sequence", "count", "event", "timestamp", "status")
    private val allowed = required + "confidence"
    private val identifier = Regex("[A-Za-z0-9._:-]{1,128}")
    private val events = setOf("entry", "exit", "ambiguous", "none")

    fun parse(body: String): GymRoomState {
        val json = JSONObject(body)
        val keys = json.keys().asSequence().toSet()
        require(keys.containsAll(required) && keys.all { it in allowed }) { "Invalid RoomState fields" }
        fun integer(name: String): Long {
            val value = json.get(name)
            require(value is Int || value is Long) { "$name must be an integer" }
            return (value as Number).toLong()
        }
        fun string(name: String): String {
            val value = json.get(name)
            require(value is String) { "$name must be a string" }
            return value
        }
        fun id(name: String): String = string(name).also {
            require(identifier.matches(it)) { "Invalid $name" }
        }
        val version = integer("schemaVersion")
        val sequence = integer("sequence")
        val count = integer("count")
        val timestamp = integer("timestamp")
        require(version == 1L && sequence >= 0 && count in 0..4_294_967_295L && timestamp >= 0)
        val event = string("event")
        val status = string("status")
        require(event in events && status in setOf("valid", "uncertain"))
        val confidence = when (val value = json.opt("confidence")) {
            null, JSONObject.NULL -> null
            is Number -> value.toDouble().also { require(it.isFinite() && it in 0.0..1.0) }
            else -> throw IllegalArgumentException("Invalid confidence")
        }
        return GymRoomState(1, id("deviceId"), id("roomId"), id("sessionId"),
            sequence, count, event, timestamp, status, confidence)
    }
}

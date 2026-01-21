package com.metarouter.analytics.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Convert Any? to JsonElement for serialization.
 * Handles nested maps and lists recursively.
 */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is List<*> -> JsonArray(map { it.toJsonElement() })
    is Map<*, *> -> JsonObject(
        entries.associate { (k, v) -> k.toString() to v.toJsonElement() }
    )
    else -> JsonPrimitive(toString())
}

/**
 * Convert Map<String, Any?> to Map<String, JsonElement>.
 */
fun Map<String, Any?>.toJsonElementMap(): Map<String, JsonElement> =
    mapValues { (_, v) -> v.toJsonElement() }

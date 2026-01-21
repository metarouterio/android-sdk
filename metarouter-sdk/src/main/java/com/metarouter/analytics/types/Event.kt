package com.metarouter.analytics.types

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Base event structure from user input.
 */
@Serializable
data class BaseEvent(
    val type: EventType,
    val event: String? = null,
    val traits: Map<String, JsonElement>? = null,
    val properties: Map<String, JsonElement>? = null,
    val timestamp: String? = null
)

/**
 * Fully enriched event payload ready for transmission.
 * Contains all required fields including context, messageId, writeKey, and sentAt.
 */
@Serializable
data class EnrichedEventPayload(
    val type: EventType,
    val event: String? = null,
    val userId: String? = null,
    val anonymousId: String,
    val groupId: String? = null,
    val traits: Map<String, JsonElement>? = null,
    val properties: Map<String, JsonElement>? = null,
    val timestamp: String,
    val context: EventContext,
    val messageId: String,
    val writeKey: String,
    val sentAt: String? = null
)

package com.metarouter.analytics.types

import kotlinx.serialization.Serializable

/**
 * Base event structure from user input.
 */
@Serializable
data class BaseEvent(
    val type: EventType,
    val event: String? = null,
    val traits: Map<String, CodableValue>? = null,
    val properties: Map<String, CodableValue>? = null,
    val timestamp: String? = null
)

/**
 * Event with identity information added by IdentityManager.
 * Intermediate step in enrichment pipeline.
 */
@Serializable
data class EventWithIdentity(
    val type: EventType,
    val event: String? = null,
    val userId: String? = null,
    val anonymousId: String,
    val groupId: String? = null,
    val traits: Map<String, CodableValue>? = null,
    val properties: Map<String, CodableValue>? = null,
    val timestamp: String
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
    val traits: Map<String, CodableValue>? = null,
    val properties: Map<String, CodableValue>? = null,
    val timestamp: String,
    val context: EventContext,
    val messageId: String,
    val writeKey: String,
    val sentAt: String? = null
)

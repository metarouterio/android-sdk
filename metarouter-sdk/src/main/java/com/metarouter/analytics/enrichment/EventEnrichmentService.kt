package com.metarouter.analytics.enrichment

import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.types.EventWithIdentity
import com.metarouter.analytics.utils.MessageIdGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Service responsible for enriching events with identity, context, and metadata.
 *
 * Enrichment Pipeline:
 * 1. BaseEvent (user input)
 * 2. Add identity (anonymousId, userId, groupId) -> EventWithIdentity
 * 3. Add context, messageId, writeKey -> EnrichedEventPayload
 * 4. Add sentAt timestamp at drain time (not handled here)
 *
 * Thread Safety:
 * - All methods are suspend and safe to call from any thread
 * - Uses thread-safe IdentityManager and DeviceContextProvider
 * - No mutable state in this service
 *
 * Per spec v1.4.0:
 * - messageId format: {epochMs}-{uuid}
 * - timestamp: ISO 8601 format with timezone
 * - anonymousId: always included (never null)
 * - context: includes device, app, OS, screen, network, library, locale, timezone
 * - advertisingId: included in device context if set
 */
class EventEnrichmentService(
    private val identityManager: IdentityManager,
    private val contextProvider: DeviceContextProvider,
    private val writeKey: String
) {

    companion object {
        private val ISO_8601_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    /**
     * Enrich a base event with all required metadata.
     *
     * @param baseEvent The event from user input
     * @return Fully enriched event payload ready for queueing
     */
    suspend fun enrichEvent(baseEvent: BaseEvent): EnrichedEventPayload {
        // Step 1: Add identity information
        val eventWithIdentity = addIdentity(baseEvent)

        // Step 2: Add context, messageId, writeKey
        return addMetadata(eventWithIdentity)
    }

    /**
     * Add identity information to base event.
     * Fetches anonymousId, userId, groupId from IdentityManager.
     */
    private suspend fun addIdentity(baseEvent: BaseEvent): EventWithIdentity {
        val anonymousId = identityManager.getAnonymousId()
        val userId = identityManager.getUserId()
        val groupId = identityManager.getGroupId()

        // Use provided timestamp or generate new one
        val timestamp = baseEvent.timestamp ?: getCurrentTimestamp()

        return EventWithIdentity(
            type = baseEvent.type,
            event = baseEvent.event,
            userId = userId,
            anonymousId = anonymousId,
            groupId = groupId,
            traits = baseEvent.traits,
            properties = baseEvent.properties,
            timestamp = timestamp
        )
    }

    /**
     * Add context, messageId, and writeKey to create fully enriched payload.
     */
    private suspend fun addMetadata(eventWithIdentity: EventWithIdentity): EnrichedEventPayload {
        // Get advertising ID from identity manager
        val advertisingId = identityManager.getAdvertisingId()

        // Get context with advertising ID
        val context = contextProvider.getContext(advertisingId)

        // Generate unique message ID
        val messageId = MessageIdGenerator.generate()

        return EnrichedEventPayload(
            type = eventWithIdentity.type,
            event = eventWithIdentity.event,
            userId = eventWithIdentity.userId,
            anonymousId = eventWithIdentity.anonymousId,
            groupId = eventWithIdentity.groupId,
            traits = eventWithIdentity.traits,
            properties = eventWithIdentity.properties,
            timestamp = eventWithIdentity.timestamp,
            context = context,
            messageId = messageId,
            writeKey = writeKey,
            sentAt = null // sentAt is added at drain time, not here
        )
    }

    /**
     * Generate current timestamp in ISO 8601 format (UTC).
     * Format: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
     */
    private fun getCurrentTimestamp(): String {
        return ISO_8601_FORMAT.format(Date())
    }
}

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
    private fun addMetadata(eventWithIdentity: EventWithIdentity): EnrichedEventPayload {
        // Get device/app context
        val context = contextProvider.getContext()

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

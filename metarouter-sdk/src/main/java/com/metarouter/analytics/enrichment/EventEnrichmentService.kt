package com.metarouter.analytics.enrichment

import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.EnrichedEventPayload
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

    /**
     * Enrich a base event with identity, context, and metadata.
     *
     * @param baseEvent The event from user input
     * @return Fully enriched event payload ready for queueing
     */
    suspend fun enrichEvent(baseEvent: BaseEvent): EnrichedEventPayload {
        // Fetch identity
        val anonymousId = identityManager.getAnonymousId()
        val userId = identityManager.getUserId()
        val groupId = identityManager.getGroupId()
        val advertisingId = identityManager.getAdvertisingId()

        // Use provided timestamp or generate new one
        val timestamp = baseEvent.timestamp ?: getCurrentTimestamp()

        // Get device/app context
        val baseContext = contextProvider.getContext()

        // Merge advertisingId into device context if present
        val context = if (advertisingId != null && baseContext.device != null) {
            baseContext.copy(device = baseContext.device.copy(advertisingId = advertisingId))
        } else {
            baseContext
        }

        // Generate unique message ID
        val messageId = MessageIdGenerator.generate()

        return EnrichedEventPayload(
            type = baseEvent.type,
            event = baseEvent.event,
            userId = userId,
            anonymousId = anonymousId,
            groupId = groupId,
            traits = baseEvent.traits,
            properties = baseEvent.properties,
            timestamp = timestamp,
            context = context,
            messageId = messageId,
            writeKey = writeKey,
            sentAt = null // sentAt is added at drain time
        )
    }

    /**
     * Generate current timestamp in ISO 8601 format (UTC).
     * Format: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
     */
    private fun getCurrentTimestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.format(Date())
    }
}

package com.metarouter.analytics.enrichment

import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.session.SessionManager
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.utils.MessageIdGenerator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Service responsible for enriching events with identity, context, and metadata.
 *
 * [sessionManager] must be the client's shared instance — a second manager
 * would race the same persisted session keys: independent mints, a
 * double-incremented sessionCount, and events inside one real session stamped
 * with different sessionIDs.
 */
internal class EventEnrichmentService(
    private val identityManager: IdentityManager,
    private val contextProvider: DeviceContextProvider,
    private val writeKey: String,
    private val sessionManager: SessionManager
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
        val contextWithAdId = if (advertisingId != null && baseContext.device != null) {
            baseContext.copy(device = baseContext.device.copy(advertisingId = advertisingId))
        } else {
            baseContext
        }

        // Bridge-sourced events carry their page facts on the BaseEvent; native events
        // leave it null and context.page stays absent, matching web SDK output.
        val contextWithPage = if (baseEvent.page != null) {
            contextWithAdId.copy(page = baseEvent.page)
        } else {
            contextWithAdId
        }

        // Every enriched event is session activity, and this is the one funnel all
        // event sources share (native calls, lifecycle events, the webview bridge),
        // so touching here is what makes the session stamp universal. The key names
        // and the epoch-ms string mirror the web SDK's generic MetaRouter session
        // (`context.providers.metarouter`), so pipeline mappings read one shape
        // from every platform — renaming any side forks them.
        val session = sessionManager.touch()
        val context = contextWithPage.copy(
            providers = mapOf(
                "metarouter" to buildJsonObject {
                    put("sessionID", session.sessionId)
                    put("sessionCount", session.sessionCount)
                }
            )
        )

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

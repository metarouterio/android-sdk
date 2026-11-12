package com.metarouter.analytics.enrichment

import com.metarouter.analytics.consent.ConsentManager
import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.network.NetworkMonitor
import com.metarouter.analytics.providers.ProviderContextManager
import com.metarouter.analytics.session.SessionManager
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.ConsentContext
import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.types.NetworkContext
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
 * with different sessionIDs. [consentManager] likewise: a second instance never
 * hears the host's `setConsent` / `clearConsent`, and would stamp a consent the
 * user has since withdrawn. [providerContextManager] too: a second instance never
 * hears `setProviderContext`. [networkMonitor] is required too, though null is
 * allowed: a default would let a construction silently ship every event without
 * `context.network`.
 */
internal class EventEnrichmentService(
    private val identityManager: IdentityManager,
    private val contextProvider: DeviceContextProvider,
    private val writeKey: String,
    private val sessionManager: SessionManager,
    private val consentManager: ConsentManager,
    private val providerContextManager: ProviderContextManager,
    private val networkMonitor: NetworkMonitor?
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
        // leave it null. The Dispatcher encodes nulls, so the wire carries `"page": null`
        // (and `"consent": null` on a pre-consent event drained from disk) where iOS and
        // web omit the key; downstream mappings and consent filters treat the two alike.
        val contextWithPage = if (baseEvent.page != null) {
            contextWithAdId.copy(page = baseEvent.page)
        } else {
            contextWithAdId
        }

        // Network is read live from the persistent monitor at enrichment, so
        // `network.wifi` is the interface at event time, not at the first event.
        // Unknown keeps the provider's value — null for the SDK's own provider. The
        // spec requires `wifi` to be a boolean whenever `network` is present, so an
        // unknown interface drops the object rather than sending `{"wifi": null}`.
        val contextWithNetwork = networkMonitor?.isWifi
            ?.let { contextWithPage.copy(network = NetworkContext(wifi = it)) }
            ?: contextWithPage

        // Every enriched event is session activity, and this is the one funnel all
        // event sources share (native calls, lifecycle events, the webview bridge),
        // so touching here is what makes the session stamp universal. The key names
        // and the epoch-ms string mirror the web SDK's generic MetaRouter session
        // (`context.providers.metarouter`), so pipeline mappings read one shape
        // from every platform — renaming any side forks them.
        val session = sessionManager.touch()
        // Consent is read here, per event, for the same reason: this funnel is
        // shared by every source. The stamp is the state at ENRICHMENT time — not
        // atomically with identity and session, but never as early as the track()
        // call: track() only schedules enrichment, so `track(); setConsent()` on
        // adjacent lines can stamp the new state, and a pre-initialize call replayed
        // at bind is stamped with whatever consent holds at replay. Once enriched, a
        // payload is immutable; a later setConsent never rewrites it. Before the
        // host's first setConsent this is the implicit default (`explicit: false`,
        // registered categories at their defaults) — a complete block the server
        // gates like implicit-consent web traffic, never an absent field. (Events a
        // pre-consent build enriched and left on disk drain without one; stamping
        // today's consent onto them would misattribute it.) The path and shape are
        // the web SDK's `context.consent`; moving or renaming it forks every
        // per-integration consent rule downstream.
        //
        // Host provider values first, the SDK's block last, so the session stamp wins
        // any collision. The API already refuses `metarouter`; this ordering is what
        // still holds for a value that reached the map another way. Flipping it — one
        // merge with the host map last — hands a customer sessionID, and nothing
        // downstream would flag it.
        val context = contextWithNetwork.copy(
            consent = ConsentContext(consentManager.getConsent()),
            providers = providerContextManager.getAll() + (
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

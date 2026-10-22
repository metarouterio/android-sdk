package com.metarouter.analytics

import android.net.Uri
import android.webkit.WebView
import com.metarouter.analytics.consent.Consent
import com.metarouter.analytics.consent.ConsentCategory
import com.metarouter.analytics.consent.ConsentSubscription
import com.metarouter.analytics.utils.Logger

/**
 * Public API for the MetaRouter Analytics SDK.
 *
 * This interface defines all methods available for tracking events, managing identity,
 * and controlling SDK behavior.
 *
 * All methods are safe to call from any thread. Event tracking methods (track, identify, etc.)
 * return immediately and process events asynchronously in the background.
 */
interface AnalyticsInterface {
    /**
     * Track a custom event with optional properties.
     *
     * @param event Name of the event (e.g., "Button Clicked", "Item Purchased")
     * @param properties Optional key-value pairs with additional event context
     */
    fun track(event: String, properties: Map<String, Any?>? = null)

    /**
     * Identify a user and associate them with traits.
     *
     * This method sets the userId that will be included in all subsequent events
     * and persists it to storage. The userId remains set until reset() is called.
     *
     * @param userId Unique identifier for the user
     * @param traits Optional key-value pairs with user attributes
     */
    fun identify(userId: String, traits: Map<String, Any?>? = null)

    /**
     * Associate the user with a group (organization, team, account).
     *
     * This method sets the groupId that will be included in all subsequent events
     * and persists it to storage. The groupId remains set until reset() is called.
     *
     * @param groupId Unique identifier for the group
     * @param traits Optional key-value pairs with group attributes
     */
    fun group(groupId: String, traits: Map<String, Any?>? = null)

    /**
     * Track a screen view in your app.
     *
     * @param name Name of the screen (e.g., "Home", "Settings", "Product Details")
     * @param properties Optional key-value pairs with screen context
     */
    fun screen(name: String, properties: Map<String, Any?>? = null)

    /**
     * Track a page view (typically used in web contexts, but available for consistency).
     *
     * @param name Name of the page
     * @param properties Optional key-value pairs with page context
     */
    fun page(name: String, properties: Map<String, Any?>? = null)

    /**
     * Alias a user ID to a new ID.
     *
     * This is used to associate an anonymous user with a known user ID after signup or login.
     * It creates an alias event and updates the userId.
     *
     * @param newUserId The new user identifier to associate with the current anonymous ID
     */
    fun alias(newUserId: String)

    /**
     * Set the advertising ID for attribution and advertising use cases.
     *
     * This is NOT automatically collected - the app must provide it after obtaining
     * user consent and fetching the GAID from Google Play Services.
     *
     * The advertising ID is persisted and restored on SDK initialization.
     * It will be included in the context.device.advertisingId field of all events.
     *
     * @param advertisingId The Google Advertising ID (GAID) or equivalent
     */
    fun setAdvertisingId(advertisingId: String)

    /**
     * Clear the advertising ID for privacy compliance.
     *
     * Call this when:
     * - User opts out of ad tracking
     * - User requests data deletion (GDPR/CCPA)
     * - User revokes consent for advertising
     */
    fun clearAdvertisingId()

    /**
     * Manually trigger a flush of all queued events.
     *
     * Events are automatically flushed based on the configured flush interval and queue size,
     * but you can call this method to force an immediate flush (e.g., before the user exits).
     *
     * This method is suspending and will complete when the flush is done.
     */
    suspend fun flush()

    /**
     * Reset the SDK to a clean state.
     *
     * This clears all identity information (userId, groupId), generates a
     * new anonymousId, clears the event queue, and resets the network circuit breaker.
     *
     * Use this method when a user logs out to ensure their data is not mixed with
     * the next user's data.
     *
     * This method is suspending and will complete when the reset is done.
     */
    suspend fun reset()

    /**
     * Get the current anonymous ID, suspending until the SDK is ready.
     *
     * If initialization is still in progress, this suspends until the
     * client is bound and ready, then returns the anonymous ID.
     * If already initialized, returns immediately.
     *
     * @return The current anonymous ID
     */
    suspend fun getAnonymousId(): String

    /**
     * The id of the current analytics session, or null before the first event
     * of the process has been enriched. Sessions are minted by events —
     * reading the id is not activity and never extends or starts one, so a
     * diagnostics poller cannot keep a session alive. Unlike [getAnonymousId],
     * this never suspends for initialization: null is the documented answer
     * whenever no session can exist yet. The same value is stamped on every
     * outbound event at `context.providers.metarouter.sessionID`.
     */
    suspend fun getSessionId(): String?

    /**
     * Enable debug logging for troubleshooting.
     *
     * When enabled, the SDK will log detailed information about initialization, event
     * tracking, network requests, and errors to the console with the [MetaRouter] prefix.
     */
    fun enableDebugLogging()

    /**
     * Get detailed debug information about the SDK state.
     *
     * Returns a map containing:
     * - lifecycle: Current lifecycle state
     * - queueLength: Number of queued events
     * - ingestionHost: Configured ingestion endpoint
     * - writeKey: Masked write key (***last4chars)
     * - flushIntervalSeconds: Flush interval in seconds
     * - anonymousId: Current anonymous ID
     * - userId: Current user ID (or null)
     * - groupId: Current group ID (or null)
     * - flushInFlight: Whether a flush is currently in progress
     * - circuitState: Current circuit breaker state
     * - maxQueueEvents: Configured max queue capacity
     *
     * @return Map of debug information
     */
    suspend fun getDebugInfo(): Map<String, Any?>

    /**
     * Enable or disable tracing for network requests.
     *
     * When enabled, the SDK will add a "Trace: true" header to batch requests,
     * which can be used for debugging and monitoring on the server side.
     *
     * @param enabled Whether to enable tracing
     */
    fun setTracing(enabled: Boolean)

    /**
     * Buffer a deep-link URL for the next `Application Opened` event.
     *
     * Call this from the receiving Activity's `onCreate` / `onNewIntent`, passing
     * `intent.data` for the URI. Use `Activity.referrer?.host` for the referrer —
     * `Intent.EXTRA_REFERRER` is documented as a `Uri`, not a String, so
     * `getStringExtra` on it is virtually always `null`.
     *
     * The next `Application Opened` event the SDK emits will carry `url` and
     * (when provided) `referring_application` properties. The buffer is one-shot —
     * subsequent `Application Opened` events without a fresh call omit the props.
     * If called multiple times before the next `Application Opened`, the most recent
     * URL wins (last-write-wins; no queue).
     *
     * Logs a warning and is a no-op when `InitOptions.trackLifecycleEvents` is `false`.
     *
     * The method is named after the *signal* the host is forwarding, not what the SDK
     * does with it — the SDK does not route, parse, or open the URL. Mirrors Segment's
     * iOS API for cross-platform consistency.
     *
     * @param uri The deep-link URI that opened the app
     * @param sourceApplication Optional referring application identifier
     */
    fun recordOpenedUrl(uri: Uri, sourceApplication: String? = null)

    /**
     * Attach the webview event bridge to a host-owned WebView.
     *
     * Pages loaded from [allowedOrigins] get a `window.metarouterBridge` object with
     * `track(name, properties)` and `page(name, properties)` methods. Calls are
     * enveloped in the page, validated and deduplicated natively, enriched with the
     * device's identity and context, and delivered through the normal event queue —
     * webview activity and native activity arrive as one user.
     *
     * Call this on the main thread when the WebView is created, **before** `loadUrl` —
     * the registrations only apply to page loads that start afterwards. The SDK never
     * discovers webviews on its own: the host owns the WebView, this call is the
     * explicit hand-off.
     *
     * Origins must be explicit (`https://host[:port]`); the wildcard `"*"` is rejected,
     * since origin scoping is what keeps arbitrary pages from injecting events into the
     * native queue. On devices whose WebView provider lacks the required features the
     * bridge logs a warning and captures nothing — it never falls back to an unscoped
     * mechanism.
     *
     * @param webView The host-owned WebView to bridge
     * @param allowedOrigins Explicit page origins allowed to produce events
     */
    fun attachWebView(webView: WebView, allowedOrigins: List<String>) {
        InterfaceDefaults.warnNotImplemented(this, "attachWebView")
    }

    // ===== Consent =====

    /**
     * Register the consent category set and each category's default posture.
     *
     * Call on [MetaRouter.Analytics.shared] BEFORE `initialize`. The registry is not
     * persisted, and until it is registered the implicit default is an empty `optOut` —
     * which the server reads as nothing blocked. `initialize` can emit lifecycle events
     * (`Application Opened`, `Session Started`) from its background setup, so a
     * registration that runs after it races those events on every launch without stored
     * consent. Registration works before `initialize` because consent never waits for a
     * client.
     *
     * A CMP payload is sparse, and the registry is what lets the SDK complete it into a
     * full block (keep known keys, drop unknown ones, fill missing ones with the
     * registered default). Default posture is opt-OUT. Re-registration replaces the set
     * and recomputes the view of any stored consent against it; it does not merge. A
     * `setConsent` that arrives first is stored as-is and completed on registration.
     * Keys are trimmed and lowercased; on a debuggable build a key that needed it, a
     * blank key, or a duplicate throws at the call (at `initialize` instead, for a
     * registration made earlier, if the host removed the SDK's startup provider).
     */
    fun registerConsentCategories(categories: List<ConsentCategory>) {
        InterfaceDefaults.warnNotImplemented(this, "registerConsentCategories")
    }

    /**
     * Push the current consent state into the SDK.
     *
     * The host owns consent acquisition (CMP SDK callback, WebView cookie, …) — feed the
     * raw payload through an adapter (e.g. `OneTrustAdapter.normalize`) and hand the
     * canonical result here. The SDK reconciles it against the registered categories,
     * persists it across launches, and stamps it on every subsequent outbound event as
     * `context.consent`. Keys are matched case-insensitively and sent lowercase. Fully
     * replaces prior state, with one exception: a snapshot whose `timestamp` is older
     * than the newest host-supplied `timestamp` since the last `clearConsent` is ignored
     * as a stale CMP replay. Untimestamped calls are never rejected and are not compared
     * with any timestamp.
     *
     * Usable before `initialize` and immediately effective: an event tracked on the next
     * line carries the new state. Consent never gates delivery — events always send, and
     * the server gates per integration on the stamped block.
     *
     * @return `false` when the snapshot was ignored as stale; the previous state stands.
     */
    fun setConsent(consent: Consent): Boolean {
        InterfaceDefaults.warnNotImplemented(this, "setConsent")
        return false
    }

    /**
     * The current reconciled consent state. Never null: before the first `setConsent`
     * (and after `clearConsent`) this is the implicit default — `explicit: false` with
     * every registered category at its default posture.
     */
    fun getConsent(): Consent {
        InterfaceDefaults.warnNotImplemented(this, "getConsent")
        return Consent(explicit = false, optOut = emptyMap())
    }

    /**
     * Wipe the stored consent state, timestamp history included, and return to the
     * implicit default. Any `setConsent` after it applies, so do not re-push a CMP
     * snapshot from before the clear. Unlike `reset()`, which clears identity but leaves
     * consent untouched.
     */
    fun clearConsent() {
        InterfaceDefaults.warnNotImplemented(this, "clearConsent")
    }

    /**
     * Subscribe to consent changes. The handler runs on the main thread, once with the
     * current value right after subscribing (posted, never inline), then on every real
     * change (value-identical `setConsent` calls do not fire).
     *
     * @return the subscription. Keep it: delivery continues until `close()`, even if
     *   the reference is dropped. After `close()` returns on the main thread, the
     *   handler is not invoked again, even for a delivery already queued.
     */
    fun subscribeToConsent(handler: (Consent) -> Unit): ConsentSubscription {
        InterfaceDefaults.warnNotImplemented(this, "subscribeToConsent")
        return ConsentSubscription {}
    }
}

/**
 * Defaults for every member added after the interface first shipped, so a host class
 * implementing [AnalyticsInterface] (a test fake, a wrapper) keeps compiling across a
 * minor SDK upgrade. The SDK's own classes implement all of them; the defaults only
 * ever run on a host implementation.
 *
 * Each one warns and does nothing, rather than throwing: a wrapper that forgets to
 * forward a new call loses that call — visible in the log — instead of either breaking
 * the host's build or crashing in production. Any member added from here on gets a
 * default for the same reason.
 *
 * The defaults also removed the compiler's check that the SDK's own classes implement
 * each member: a signature that drifts silently stops being the override, and the call
 * becomes a logged no-op. [hits] records every `Class.method` that fell through — a
 * set, bounded by the number of implementing classes — and tests drive every member on
 * the SDK's classes and assert it stays empty.
 */
internal object InterfaceDefaults {
    private val hitsLock = Any()
    private val recorded = HashSet<String>()

    val hits: Set<String>
        get() = synchronized(hitsLock) { recorded.toSet() }

    fun reset() = synchronized(hitsLock) { recorded.clear() }

    /**
     * Warns once per class and method: a wrapper that forwards `track` but not
     * `getConsent` would otherwise log on every event.
     */
    fun warnNotImplemented(receiver: Any, method: String) {
        // simpleName is empty for anonymous classes (`object : AnalyticsInterface by
        // delegate`), which would merge every such wrapper into one blank key.
        val type = receiver.javaClass.simpleName.ifEmpty { receiver.javaClass.name }
        val first = synchronized(hitsLock) { recorded.add("$type.$method") }
        if (!first) return
        Logger.warn(
            "$method is not implemented by $type — the call was ignored. A class wrapping the SDK must " +
                "forward it to MetaRouter.Analytics. Logged once."
        )
    }
}

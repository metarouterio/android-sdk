package com.metarouter.analytics

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
}

package com.metarouter.analytics

/**
 * Kotlin-idiomatic extension functions for AnalyticsInterface.
 *
 * These extensions provide varargs overloads that accept pairs instead of maps,
 * making the API more natural for Kotlin developers.
 *
 * Example:
 * ```
 * // Instead of:
 * analytics.track("Purchase", mapOf("item" to "Premium Plan", "price" to 29.99))
 *
 * // You can write:
 * analytics.track("Purchase", "item" to "Premium Plan", "price" to 29.99)
 * ```
 */

/**
 * Track a custom event with optional properties using varargs pairs.
 *
 * @param event Name of the event (e.g., "Button Clicked", "Item Purchased")
 * @param properties Pairs of property key-value pairs (e.g., "key" to value)
 */
fun AnalyticsInterface.track(event: String, vararg properties: Pair<String, Any?>) {
    track(event, if (properties.isEmpty()) null else properties.toMap())
}

/**
 * Identify a user and associate them with traits using varargs pairs.
 *
 * @param userId Unique identifier for the user
 * @param traits Pairs of user trait key-value pairs (e.g., "name" to "Alice")
 */
fun AnalyticsInterface.identify(userId: String, vararg traits: Pair<String, Any?>) {
    identify(userId, if (traits.isEmpty()) null else traits.toMap())
}

/**
 * Associate the user with a group using varargs pairs.
 *
 * @param groupId Unique identifier for the group
 * @param traits Pairs of group trait key-value pairs (e.g., "company" to "Acme")
 */
fun AnalyticsInterface.group(groupId: String, vararg traits: Pair<String, Any?>) {
    group(groupId, if (traits.isEmpty()) null else traits.toMap())
}

/**
 * Track a screen view with optional properties using varargs pairs.
 *
 * @param name Name of the screen (e.g., "Home", "Settings")
 * @param properties Pairs of property key-value pairs (e.g., "referrer" to "notification")
 */
fun AnalyticsInterface.screen(name: String, vararg properties: Pair<String, Any?>) {
    screen(name, if (properties.isEmpty()) null else properties.toMap())
}

/**
 * Track a page view with optional properties using varargs pairs.
 *
 * @param name Name of the page
 * @param properties Pairs of property key-value pairs (e.g., "url" to "/home")
 */
fun AnalyticsInterface.page(name: String, vararg properties: Pair<String, Any?>) {
    page(name, if (properties.isEmpty()) null else properties.toMap())
}

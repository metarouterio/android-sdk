package com.metarouter.analytics

/**
 * Represents a queued analytics method call that will be replayed
 * once the real client is bound to the proxy.
 *
 * Used by [AnalyticsProxy] to buffer calls made before SDK initialization completes.
 */
sealed class PendingCall {
    data class Track(val event: String, val properties: Map<String, Any?>?) : PendingCall()
    data class Identify(val userId: String, val traits: Map<String, Any?>?) : PendingCall()
    data class Group(val groupId: String, val traits: Map<String, Any?>?) : PendingCall()
    data class Screen(val name: String, val properties: Map<String, Any?>?) : PendingCall()
    data class Page(val name: String, val properties: Map<String, Any?>?) : PendingCall()
    data class Alias(val newUserId: String) : PendingCall()
    data class SetAdvertisingId(val advertisingId: String) : PendingCall()
    object ClearAdvertisingId : PendingCall()
    object Flush : PendingCall()
    object Reset : PendingCall()
    object EnableDebugLogging : PendingCall()
}

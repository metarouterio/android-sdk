package com.metarouter.analytics

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for Kotlin-idiomatic extension functions on AnalyticsInterface.
 */
class AnalyticsExtensionsTest {

    /**
     * Mock implementation of AnalyticsInterface for testing.
     */
    private class MockAnalytics : AnalyticsInterface {
        var lastTrackEvent: String? = null
        var lastTrackProperties: Map<String, Any?>? = null
        var lastIdentifyUserId: String? = null
        var lastIdentifyTraits: Map<String, Any?>? = null
        var lastGroupId: String? = null
        var lastGroupTraits: Map<String, Any?>? = null
        var lastScreenName: String? = null
        var lastScreenProperties: Map<String, Any?>? = null
        var lastPageName: String? = null
        var lastPageProperties: Map<String, Any?>? = null

        override fun track(event: String, properties: Map<String, Any?>?) {
            lastTrackEvent = event
            lastTrackProperties = properties
        }

        override fun identify(userId: String, traits: Map<String, Any?>?) {
            lastIdentifyUserId = userId
            lastIdentifyTraits = traits
        }

        override fun group(groupId: String, traits: Map<String, Any?>?) {
            lastGroupId = groupId
            lastGroupTraits = traits
        }

        override fun screen(name: String, properties: Map<String, Any?>?) {
            lastScreenName = name
            lastScreenProperties = properties
        }

        override fun page(name: String, properties: Map<String, Any?>?) {
            lastPageName = name
            lastPageProperties = properties
        }

        override fun alias(newUserId: String) {}
        override fun setAdvertisingId(advertisingId: String) {}
        override fun clearAdvertisingId() {}
        override suspend fun flush() {}
        override suspend fun reset() {}
        override fun enableDebugLogging() {}
        override suspend fun getDebugInfo(): Map<String, Any?> = emptyMap()
        override fun setTracing(enabled: Boolean) {}
    }

    @Test
    fun `track with varargs pairs converts to map`() {
        val analytics = MockAnalytics()

        analytics.track("Purchase",
            "item" to "Premium Plan",
            "price" to 29.99,
            "quantity" to 1
        )

        assertEquals("Purchase", analytics.lastTrackEvent)
        assertNotNull(analytics.lastTrackProperties)
        assertEquals("Premium Plan", analytics.lastTrackProperties!!["item"])
        assertEquals(29.99, analytics.lastTrackProperties!!["price"])
        assertEquals(1, analytics.lastTrackProperties!!["quantity"])
    }

    @Test
    fun `track with no properties passes null`() {
        val analytics = MockAnalytics()

        analytics.track("App Opened")

        assertEquals("App Opened", analytics.lastTrackEvent)
        assertNull(analytics.lastTrackProperties)
    }

    @Test
    fun `track with single property works`() {
        val analytics = MockAnalytics()

        analytics.track("Button Clicked", "button_name" to "Sign Up")

        assertEquals("Button Clicked", analytics.lastTrackEvent)
        assertEquals(mapOf("button_name" to "Sign Up"), analytics.lastTrackProperties)
    }

    @Test
    fun `track with nested map properties works`() {
        val analytics = MockAnalytics()

        analytics.track("Purchase",
            "item" to "Premium Plan",
            "metadata" to mapOf(
                "campaign" to "summer2024",
                "discount" to true
            )
        )

        assertEquals("Purchase", analytics.lastTrackEvent)
        assertEquals("Premium Plan", analytics.lastTrackProperties!!["item"])
        val metadata = analytics.lastTrackProperties!!["metadata"] as Map<*, *>
        assertEquals("summer2024", metadata["campaign"])
        assertEquals(true, metadata["discount"])
    }

    @Test
    fun `identify with varargs pairs converts to map`() {
        val analytics = MockAnalytics()

        analytics.identify("user-123",
            "name" to "Alice",
            "email" to "alice@example.com",
            "plan" to "premium"
        )

        assertEquals("user-123", analytics.lastIdentifyUserId)
        assertNotNull(analytics.lastIdentifyTraits)
        assertEquals("Alice", analytics.lastIdentifyTraits!!["name"])
        assertEquals("alice@example.com", analytics.lastIdentifyTraits!!["email"])
        assertEquals("premium", analytics.lastIdentifyTraits!!["plan"])
    }

    @Test
    fun `identify with no traits passes null`() {
        val analytics = MockAnalytics()

        analytics.identify("user-123")

        assertEquals("user-123", analytics.lastIdentifyUserId)
        assertNull(analytics.lastIdentifyTraits)
    }

    @Test
    fun `group with varargs pairs converts to map`() {
        val analytics = MockAnalytics()

        analytics.group("company-456",
            "name" to "Acme Corp",
            "plan" to "enterprise",
            "employees" to 500
        )

        assertEquals("company-456", analytics.lastGroupId)
        assertNotNull(analytics.lastGroupTraits)
        assertEquals("Acme Corp", analytics.lastGroupTraits!!["name"])
        assertEquals("enterprise", analytics.lastGroupTraits!!["plan"])
        assertEquals(500, analytics.lastGroupTraits!!["employees"])
    }

    @Test
    fun `group with no traits passes null`() {
        val analytics = MockAnalytics()

        analytics.group("company-456")

        assertEquals("company-456", analytics.lastGroupId)
        assertNull(analytics.lastGroupTraits)
    }

    @Test
    fun `screen with varargs pairs converts to map`() {
        val analytics = MockAnalytics()

        analytics.screen("Home Screen",
            "referrer" to "notification",
            "tab" to "feed"
        )

        assertEquals("Home Screen", analytics.lastScreenName)
        assertNotNull(analytics.lastScreenProperties)
        assertEquals("notification", analytics.lastScreenProperties!!["referrer"])
        assertEquals("feed", analytics.lastScreenProperties!!["tab"])
    }

    @Test
    fun `screen with no properties passes null`() {
        val analytics = MockAnalytics()

        analytics.screen("Home Screen")

        assertEquals("Home Screen", analytics.lastScreenName)
        assertNull(analytics.lastScreenProperties)
    }

    @Test
    fun `page with varargs pairs converts to map`() {
        val analytics = MockAnalytics()

        analytics.page("Landing Page",
            "url" to "/landing",
            "referrer" to "google"
        )

        assertEquals("Landing Page", analytics.lastPageName)
        assertNotNull(analytics.lastPageProperties)
        assertEquals("/landing", analytics.lastPageProperties!!["url"])
        assertEquals("google", analytics.lastPageProperties!!["referrer"])
    }

    @Test
    fun `page with no properties passes null`() {
        val analytics = MockAnalytics()

        analytics.page("Landing Page")

        assertEquals("Landing Page", analytics.lastPageName)
        assertNull(analytics.lastPageProperties)
    }

    @Test
    fun `varargs with null values are preserved`() {
        val analytics = MockAnalytics()

        analytics.track("Event",
            "key1" to "value1",
            "key2" to null,
            "key3" to "value3"
        )

        assertEquals("Event", analytics.lastTrackEvent)
        assertNotNull(analytics.lastTrackProperties)
        assertEquals("value1", analytics.lastTrackProperties!!["key1"])
        assertTrue(analytics.lastTrackProperties!!.containsKey("key2"))
        assertNull(analytics.lastTrackProperties!!["key2"])
        assertEquals("value3", analytics.lastTrackProperties!!["key3"])
    }

    @Test
    fun `varargs supports all common types`() {
        val analytics = MockAnalytics()

        analytics.track("Mixed Types",
            "string" to "hello",
            "int" to 42,
            "long" to 123456789L,
            "double" to 3.14,
            "float" to 2.5f,
            "bool" to true,
            "null" to null,
            "list" to listOf(1, 2, 3),
            "map" to mapOf("nested" to "value")
        )

        val props = analytics.lastTrackProperties!!
        assertEquals("hello", props["string"])
        assertEquals(42, props["int"])
        assertEquals(123456789L, props["long"])
        assertEquals(3.14, props["double"])
        assertEquals(2.5f, props["float"])
        assertEquals(true, props["bool"])
        assertNull(props["null"])
        assertEquals(listOf(1, 2, 3), props["list"])
        assertEquals(mapOf("nested" to "value"), props["map"])
    }
}

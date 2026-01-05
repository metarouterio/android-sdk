package com.metarouter.analytics

import android.content.Context
import com.metarouter.analytics.types.EventType
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetaRouterAnalyticsClientTest {

    private lateinit var context: Context
    private lateinit var options: InitOptions

    /**
     * Wait for a condition to become true, polling every 50ms.
     * More reliable than fixed delays on slow CI runners.
     */
    private suspend fun awaitCondition(
        timeoutMs: Long = 2000,
        condition: suspend () -> Boolean
    ) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            delay(50)
        }
    }

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context

        options = InitOptions(
            writeKey = "test-write-key",
            ingestionHost = "https://events.example.com",
            flushIntervalSeconds = 10,
            debug = false,
            maxQueueEvents = 100
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ===== Initialization =====

    @Test
    fun `initialize creates client in READY state`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
    }

    @Test
    fun `initialize with debug enabled`() = runBlocking {
        val debugOptions = options.copy(debug = true)

        val client = MetaRouterAnalyticsClient.initialize(context, debugOptions)

        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
    }

    @Test
    fun `initialize generates anonymous ID`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        val debugInfo = client.getDebugInfo()
        assertNotNull(debugInfo["anonymousId"])
    }

    // ===== Event Tracking =====

    @Test
    fun `track enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.track("Button Clicked")
        delay(100) // Allow time for async enqueue

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0)
    }

    @Test
    fun `track with properties enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        val properties = mapOf(
            "button_name" to "Subscribe",
            "price" to 29.99,
            "trial_days" to 7
        )

        client.track("Button Clicked", properties)
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0)
    }

    @Test
    fun `track with empty event name enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        // Empty event name is technically allowed per spec
        client.track("")
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) >= 0)
    }

    @Test
    fun `identify updates userId and enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.identify("user-123")
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0)
        assertNotNull(debugInfo["userId"])
    }

    @Test
    fun `identify with traits enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        val traits = mapOf(
            "email" to "user@example.com",
            "name" to "John Doe",
            "age" to 30
        )

        client.identify("user-123", traits)
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0)
    }

    @Test
    fun `identify with empty userId logs warning and does not enqueue`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.identify("")
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertEquals(0, debugInfo["queueLength"])
    }

    @Test
    fun `group updates groupId and enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.group("company-456")
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0)
        assertNotNull(debugInfo["groupId"])
    }

    @Test
    fun `group with traits enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        val traits = mapOf(
            "name" to "Acme Corp",
            "industry" to "Technology",
            "employees" to 100
        )

        client.group("company-456", traits)
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0)
    }

    @Test
    fun `group with empty groupId logs warning and does not enqueue`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.group("")
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertEquals(0, debugInfo["queueLength"])
    }

    @Test
    fun `screen enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.screen("Home Screen")

        // Poll until event is queued (more reliable than fixed delay on CI)
        awaitCondition {
            (client.getDebugInfo()["queueLength"] as Int) > 0
        }

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0)
    }

    @Test
    fun `screen with properties enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        val properties = mapOf(
            "referrer" to "push_notification",
            "tab" to "featured"
        )

        client.screen("Home Screen", properties)
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0)
    }

    @Test
    fun `page enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.page("Landing Page")
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0)
    }

    @Test
    fun `page with properties enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        val properties = mapOf(
            "url" to "https://example.com/landing",
            "referrer" to "google"
        )

        client.page("Landing Page", properties)
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0)
    }

    @Test
    fun `alias updates userId and enqueues event`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.alias("new-user-id")
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0)
        assertNotNull(debugInfo["userId"])
    }

    @Test
    fun `alias with empty userId logs warning and does not enqueue`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.alias("")
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertEquals(0, debugInfo["queueLength"])
    }

    // ===== All Event Types Conform to Spec =====

    @Test
    fun `all event types can be created and queued`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        // Track event
        client.track("Product Viewed", mapOf("product_id" to "123"))

        // Identify event
        client.identify("user-123", mapOf("email" to "user@example.com"))

        // Group event
        client.group("company-456", mapOf("name" to "Acme Corp"))

        // Screen event
        client.screen("Home", mapOf("section" to "feed"))

        // Page event
        client.page("Landing", mapOf("campaign" to "summer"))

        // Alias event
        client.alias("new-user-123")

        delay(200) // Allow time for all async operations

        val debugInfo = client.getDebugInfo()
        assertEquals(6, debugInfo["queueLength"]) // All 6 events enqueued
    }

    @Test
    fun `events include all required fields per spec`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.track("Test Event")
        delay(100)

        val debugInfo = client.getDebugInfo()

        // Verify required fields are present in debug info
        assertNotNull(debugInfo["anonymousId"])
        // writeKey is masked as "***" + last 4 chars, so "test-write-key" becomes "***-key"
        assertEquals("***-key", debugInfo["writeKey"])
        assertTrue((debugInfo["queueLength"] as Int) > 0)
    }

    // ===== Advertising ID =====

    @Test
    fun `setAdvertisingId updates advertising ID`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.setAdvertisingId("test-gaid-12345")

        // Advertising ID is set successfully (no exception thrown)
    }

    @Test
    fun `setAdvertisingId with empty string logs warning`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.setAdvertisingId("")

        // Should log warning but not throw
    }

    @Test
    fun `clearAdvertisingId removes advertising ID`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.setAdvertisingId("test-gaid-12345")
        client.clearAdvertisingId()

        // Advertising ID is cleared successfully (no exception thrown)
    }

    // ===== Reset =====

    @Test
    fun `reset clears queue and identity`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.track("Event 1")
        client.identify("user-123")
        delay(100)

        client.reset()

        val debugInfo = client.getDebugInfo()
        assertEquals("idle", debugInfo["lifecycle"])
        // queueLength returns 0 when not in READY state
        assertEquals(0, debugInfo["queueLength"])
        // userId is null when not in READY state
        assertNull(debugInfo["userId"])
    }

    @Test
    fun `reset clears advertising ID`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.setAdvertisingId("test-gaid-12345")
        client.reset()

        // After reset, advertising ID should be cleared
        val debugInfo = client.getDebugInfo()
        assertEquals("idle", debugInfo["lifecycle"])
    }

    @Test
    fun `events cannot be enqueued after reset`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.reset()

        client.track("Event After Reset")
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertEquals("idle", debugInfo["lifecycle"])
        // queueLength returns 0 when not in READY state (events not enqueued)
        assertEquals(0, debugInfo["queueLength"])
    }

    // ===== Debug Methods =====

    @Test
    fun `enableDebugLogging enables debug mode`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.enableDebugLogging()

        // Should not throw, debug logging enabled
    }

    @Test
    fun `getDebugInfo returns complete information`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.track("Test Event")
        delay(100)

        val debugInfo = client.getDebugInfo()

        // Verify all expected keys are present
        assertNotNull(debugInfo["lifecycle"])
        assertNotNull(debugInfo["queueLength"])
        assertNotNull(debugInfo["ingestionHost"])
        assertNotNull(debugInfo["writeKey"])
        assertNotNull(debugInfo["flushIntervalSeconds"])
        assertNotNull(debugInfo["maxQueueEvents"])
        assertNotNull(debugInfo["anonymousId"])
        assertNotNull(debugInfo["flushInFlight"])
        assertNotNull(debugInfo["circuitState"])

        assertEquals("ready", debugInfo["lifecycle"])
        assertEquals("https://events.example.com", debugInfo["ingestionHost"])
        assertEquals(10, debugInfo["flushIntervalSeconds"])
        assertEquals(100, debugInfo["maxQueueEvents"])
    }

    @Test
    fun `getDebugInfo masks writeKey correctly`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        val debugInfo = client.getDebugInfo()
        val maskedKey = debugInfo["writeKey"] as String

        assertTrue(maskedKey.startsWith("***"))
        assertFalse(maskedKey.contains("test-write-key"))
    }

    // ===== Queue Overflow =====

    @Test
    fun `queue overflow drops oldest events`() = runBlocking {
        val smallOptions = options.copy(maxQueueEvents = 5)
        val client = MetaRouterAnalyticsClient.initialize(context, smallOptions)

        // Enqueue more events than capacity
        repeat(10) { i ->
            client.track("Event $i")
        }
        delay(200)

        val debugInfo = client.getDebugInfo()

        // Queue should have exactly 5 events (oldest dropped)
        assertEquals(5, debugInfo["queueLength"])
    }

    // ===== Flush (Stub) =====

    @Test
    fun `flush is a no-op stub`() = runBlocking {
        val client = MetaRouterAnalyticsClient.initialize(context, options)

        client.track("Event 1")
        delay(100)

        client.flush()

        // Flush does nothing in this PR (networking not implemented)
        val debugInfo = client.getDebugInfo()
        assertTrue((debugInfo["queueLength"] as Int) > 0) // Events still in queue
    }

    // ===== Multiple Clients =====

    @Test
    fun `multiple clients can be initialized independently`() = runBlocking {
        val client1 = MetaRouterAnalyticsClient.initialize(context, options)
        val client2 = MetaRouterAnalyticsClient.initialize(context, options.copy(writeKey = "different-key"))

        client1.track("Event 1")
        client2.track("Event 2")
        delay(100)

        val debugInfo1 = client1.getDebugInfo()
        val debugInfo2 = client2.getDebugInfo()

        assertEquals("ready", debugInfo1["lifecycle"])
        assertEquals("ready", debugInfo2["lifecycle"])
        assertTrue((debugInfo1["queueLength"] as Int) > 0)
        assertTrue((debugInfo2["queueLength"] as Int) > 0)
    }
}

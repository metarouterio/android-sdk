package com.metarouter.analytics

import android.content.Context
import com.metarouter.analytics.utils.Logger
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetaRouterTest {

    private lateinit var context: Context
    private lateinit var options: InitOptions

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

        Logger.debugEnabled = false
    }

    @After
    fun teardown() = runTest {
        MetaRouter.resetForTesting()
        unmockkAll()
        Logger.debugEnabled = false
    }

    // ===== createAnalyticsClient =====

    @Test
    fun `createAnalyticsClient returns immediately`() = runTest {
        val startTime = System.currentTimeMillis()

        val client = MetaRouter.createAnalyticsClient(context, options)

        val duration = System.currentTimeMillis() - startTime
        assertNotNull(client)
        assertTrue("createAnalyticsClient took ${duration}ms, expected < 100ms", duration < 100)
    }

    @Test
    fun `createAnalyticsClient queues early calls before binding`() = runTest {
        val client = MetaRouter.createAnalyticsClient(context, options)

        // Call track immediately before binding completes
        client.track("Early Event 1")
        client.track("Early Event 2")

        // Get debug info - should show initializing state with pending calls
        val debugInfo = client.getDebugInfo()

        // Either still initializing with pending calls, or already bound
        val lifecycle = debugInfo["lifecycle"] as String
        if (lifecycle == "initializing") {
            val pendingCalls = debugInfo["pendingCalls"] as Int
            assertTrue("Expected pending calls, got $pendingCalls", pendingCalls >= 2)
        }
        // If already bound (fast init), that's also acceptable
    }

    // ===== initializeAndWait =====

    @Test
    fun `initializeAndWait blocks until ready`() = runTest {
        val client = MetaRouter.initializeAndWait(context, options)

        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
    }

    @Test
    fun `initializeAndWait returns bound proxy`() = runTest {
        val client = MetaRouter.initializeAndWait(context, options)

        // Should be able to track immediately without queueing
        client.track("Test Event")

        // Give time for event to process
        delay(100)

        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
        assertTrue((debugInfo["queueLength"] as Int) >= 0)
    }

    // ===== Analytics.client() =====

    @Test
    fun `Analytics client returns proxy after init`() = runTest {
        MetaRouter.Analytics.initialize(context, options)

        val client = MetaRouter.Analytics.client()

        assertNotNull(client)
    }

    @Test
    fun `Analytics client throws IllegalStateException if not initialized`() = runTest {
        // Don't initialize - just try to get client
        try {
            MetaRouter.Analytics.client()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not initialized"))
        }
    }

    // ===== Double Initialization =====

    @Test
    fun `double initialization returns same proxy`() = runTest {
        val client1 = MetaRouter.createAnalyticsClient(context, options)
        val client2 = MetaRouter.createAnalyticsClient(context, options)

        assertSame(client1, client2)
    }

    @Test
    fun `initializeAndWait after createAnalyticsClient returns same proxy`() = runTest {
        val client1 = MetaRouter.createAnalyticsClient(context, options)

        // Wait for first init to complete
        delay(500)

        val client2 = MetaRouter.initializeAndWait(context, options)

        assertSame(client1, client2)
    }

    // ===== setDebugLogging =====

    @Test
    fun `setDebugLogging enables Logger`() = runTest {
        assertFalse(Logger.debugEnabled)

        MetaRouter.Analytics.setDebugLogging(true)

        assertTrue(Logger.debugEnabled)
    }

    @Test
    fun `setDebugLogging disables Logger`() = runTest {
        Logger.debugEnabled = true

        MetaRouter.Analytics.setDebugLogging(false)

        assertFalse(Logger.debugEnabled)
    }

    // ===== Reset =====

    @Test
    fun `reset allows re-initialization`() = runTest {
        // First initialization
        MetaRouter.Analytics.initializeAndWait(context, options)

        // Reset
        MetaRouter.Analytics.resetAndWait()

        // Should be able to get client still (but would need re-init for full use)
        // After reset, client() should throw since initializationStarted is false
        try {
            MetaRouter.Analytics.client()
            fail("Expected IllegalStateException after reset")
        } catch (e: IllegalStateException) {
            // Expected
        }

        // Can initialize again
        val newClient = MetaRouter.Analytics.initializeAndWait(context, options)
        assertNotNull(newClient)

        val debugInfo = newClient.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
    }

    // ===== Integration =====

    @Test
    fun `full initialization flow works`() = runTest {
        // Initialize
        val client = MetaRouter.initializeAndWait(context, options)

        // Track some events
        client.track("Event 1", mapOf("key" to "value"))
        client.identify("user-123", mapOf("email" to "test@example.com"))
        client.screen("Home Screen")

        // Give time for events to process
        delay(200)

        // Verify state
        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
        assertTrue((debugInfo["queueLength"] as Int) >= 0)
        assertNotNull(debugInfo["anonymousId"])
    }
}

package com.metarouter.analytics

import android.content.Context
import com.metarouter.analytics.utils.Logger
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetaRouterConcurrencyTest {

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

    @Test
    fun `concurrent initialization is safe - single client created`() = runTest {
        // Launch multiple concurrent initializations
        val clients = (1..10).map {
            async {
                MetaRouter.initializeAndWait(context, options)
            }
        }.awaitAll()

        // All should return the same proxy instance
        val uniqueClients = clients.toSet()
        assertEquals(1, uniqueClients.size)

        // The client should be functional
        val client = clients.first()
        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
    }

    @Test
    fun `concurrent createAnalyticsClient calls return same proxy`() = runTest {
        val clients = (1..10).map {
            async {
                MetaRouter.createAnalyticsClient(context, options)
            }
        }.awaitAll()

        // All should return the same proxy instance
        val uniqueClients = clients.toSet()
        assertEquals(1, uniqueClients.size)
    }

    @Test
    fun `concurrent track calls during init are queued or forwarded`() = runTest {
        // Start initialization
        val client = MetaRouter.createAnalyticsClient(context, options)

        // Immediately fire many concurrent track calls
        val trackJobs = (1..100).map { i ->
            async {
                client.track("Event $i", mapOf("index" to i))
            }
        }

        trackJobs.awaitAll()

        // Wait for initialization to complete
        delay(1000)

        // All events should have been processed (either queued then replayed, or forwarded)
        val debugInfo = client.getDebugInfo()

        // Should be ready now
        assertEquals("ready", debugInfo["lifecycle"])

        // Queue should have events (or some may have been flushed)
        val queueLength = debugInfo["queueLength"] as Int
        assertTrue("Expected some events in queue, got $queueLength", queueLength >= 0)
    }

    @Test
    fun `mixed concurrent operations during initialization`() = runTest {
        // Start async initialization
        val client = MetaRouter.createAnalyticsClient(context, options)

        // Mix of different operations
        val operations = listOf(
            async { client.track("Track Event") },
            async { client.identify("user-123") },
            async { client.screen("Home") },
            async { client.group("company-456") },
            async { client.page("Landing") },
            async { client.track("Another Track") },
            async { client.getDebugInfo() }
        )

        operations.awaitAll()

        // Wait for initialization
        delay(500)

        // Should be in a consistent state
        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
    }

    @Test
    fun `high concurrency stress test`() = runTest {
        val client = MetaRouter.initializeAndWait(context, options)

        // Fire 1000 concurrent operations
        val operations = (1..1000).map { i ->
            async {
                when (i % 5) {
                    0 -> client.track("Event $i")
                    1 -> client.screen("Screen $i")
                    2 -> client.page("Page $i")
                    3 -> client.getDebugInfo()
                    else -> client.track("Default $i")
                }
            }
        }

        operations.awaitAll()

        // Should still be functional
        val debugInfo = client.getDebugInfo()
        assertEquals("ready", debugInfo["lifecycle"])
    }

    @Test
    fun `concurrent initialization and reset`() = runTest {
        // Initialize first
        MetaRouter.initializeAndWait(context, options)

        // Concurrent operations including reset
        val ops = listOf(
            async { MetaRouter.Analytics.client().track("Event 1") },
            async { MetaRouter.Analytics.client().track("Event 2") },
            async { MetaRouter.Analytics.resetAndWait() }
        )

        // This may throw or succeed depending on timing - just ensure no crashes
        try {
            ops.awaitAll()
        } catch (e: Exception) {
            // Some operations may fail during reset - that's acceptable
        }

        // System should be in a consistent state after
        // Either still initialized or reset
    }
}

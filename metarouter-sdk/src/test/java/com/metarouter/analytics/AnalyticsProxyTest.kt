package com.metarouter.analytics

import com.metarouter.analytics.utils.Logger
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnalyticsProxyTest {

    private lateinit var proxy: AnalyticsProxy
    private lateinit var mockClient: AnalyticsInterface

    @Before
    fun setup() {
        proxy = AnalyticsProxy()
        mockClient = mockk(relaxed = true)
        Logger.debugEnabled = false
    }

    @After
    fun teardown() {
        unmockkAll()
        Logger.debugEnabled = false
    }

    // ===== Queuing Before Binding =====

    @Test
    fun `queues track calls before binding`() = runTest {
        proxy.track("Event 1")
        proxy.track("Event 2", mapOf("key" to "value"))

        assertEquals(2, proxy.pendingCallCount())
        assertFalse(proxy.isBound())
    }

    @Test
    fun `queues identify calls before binding`() = runTest {
        proxy.identify("user-123")
        proxy.identify("user-456", mapOf("email" to "test@example.com"))

        assertEquals(2, proxy.pendingCallCount())
    }

    @Test
    fun `queues group calls before binding`() = runTest {
        proxy.group("group-123")
        proxy.group("group-456", mapOf("name" to "Acme"))

        assertEquals(2, proxy.pendingCallCount())
    }

    @Test
    fun `queues screen calls before binding`() = runTest {
        proxy.screen("Home")
        proxy.screen("Settings", mapOf("tab" to "profile"))

        assertEquals(2, proxy.pendingCallCount())
    }

    @Test
    fun `queues page calls before binding`() = runTest {
        proxy.page("Landing")
        proxy.page("Pricing", mapOf("source" to "nav"))

        assertEquals(2, proxy.pendingCallCount())
    }

    @Test
    fun `queues alias calls before binding`() = runTest {
        proxy.alias("new-user-id")

        assertEquals(1, proxy.pendingCallCount())
    }

    @Test
    fun `queues flush calls before binding`() = runTest {
        proxy.flush()

        assertEquals(1, proxy.pendingCallCount())
    }

    @Test
    fun `queues reset calls before binding`() = runTest {
        proxy.reset()

        assertEquals(1, proxy.pendingCallCount())
    }

    @Test
    fun `queues enableDebugLogging calls before binding`() = runTest {
        proxy.enableDebugLogging()

        assertEquals(1, proxy.pendingCallCount())
        // Also enables Logger immediately
        assertTrue(Logger.debugEnabled)
    }

    @Test
    fun `queues setAdvertisingId calls before binding`() = runTest {
        proxy.setAdvertisingId("test-gaid-123")

        assertEquals(1, proxy.pendingCallCount())
    }

    @Test
    fun `queues clearAdvertisingId calls before binding`() = runTest {
        proxy.clearAdvertisingId()

        assertEquals(1, proxy.pendingCallCount())
    }

    // ===== Replay After Binding =====

    @Test
    fun `replays all queued calls after binding in order`() = runTest {
        val callOrder = mutableListOf<String>()

        coEvery { mockClient.track(any(), any()) } answers {
            callOrder.add("track:${firstArg<String>()}")
        }
        coEvery { mockClient.identify(any(), any()) } answers {
            callOrder.add("identify:${firstArg<String>()}")
        }
        coEvery { mockClient.screen(any(), any()) } answers {
            callOrder.add("screen:${firstArg<String>()}")
        }

        proxy.track("Event 1")
        proxy.identify("user-123")
        proxy.track("Event 2")
        proxy.screen("Home")

        proxy.bind(mockClient)

        assertEquals(
            listOf("track:Event 1", "identify:user-123", "track:Event 2", "screen:Home"),
            callOrder
        )
        assertEquals(0, proxy.pendingCallCount())
        assertTrue(proxy.isBound())
    }

    @Test
    fun `replays track with correct parameters`() = runTest {
        val properties = mapOf("key" to "value", "count" to 42)
        proxy.track("Test Event", properties)

        proxy.bind(mockClient)

        verify { mockClient.track("Test Event", properties) }
    }

    @Test
    fun `replays identify with correct parameters`() = runTest {
        val traits = mapOf("email" to "test@example.com")
        proxy.identify("user-123", traits)

        proxy.bind(mockClient)

        verify { mockClient.identify("user-123", traits) }
    }

    @Test
    fun `replays group with correct parameters`() = runTest {
        val traits = mapOf("name" to "Acme Corp")
        proxy.group("group-456", traits)

        proxy.bind(mockClient)

        verify { mockClient.group("group-456", traits) }
    }

    @Test
    fun `replays flush call`() = runTest {
        proxy.flush()

        proxy.bind(mockClient)

        coVerify { mockClient.flush() }
    }

    @Test
    fun `replays reset call`() = runTest {
        proxy.reset()

        proxy.bind(mockClient)

        coVerify { mockClient.reset() }
    }

    @Test
    fun `replays enableDebugLogging call`() = runTest {
        proxy.enableDebugLogging()

        proxy.bind(mockClient)

        verify { mockClient.enableDebugLogging() }
    }

    @Test
    fun `replays setAdvertisingId call`() = runTest {
        proxy.setAdvertisingId("test-gaid-456")

        proxy.bind(mockClient)

        verify { mockClient.setAdvertisingId("test-gaid-456") }
    }

    @Test
    fun `replays clearAdvertisingId call`() = runTest {
        proxy.clearAdvertisingId()

        proxy.bind(mockClient)

        verify { mockClient.clearAdvertisingId() }
    }

    // ===== Direct Forwarding After Binding =====

    @Test
    fun `forwards track directly after binding`() = runTest {
        proxy.bind(mockClient)

        proxy.track("Direct Event", mapOf("direct" to true))

        verify { mockClient.track("Direct Event", mapOf("direct" to true)) }
        assertEquals(0, proxy.pendingCallCount())
    }

    @Test
    fun `forwards identify directly after binding`() = runTest {
        proxy.bind(mockClient)

        proxy.identify("user-direct", mapOf("direct" to true))

        verify { mockClient.identify("user-direct", mapOf("direct" to true)) }
    }

    @Test
    fun `forwards flush directly after binding`() = runTest {
        proxy.bind(mockClient)

        proxy.flush()

        coVerify { mockClient.flush() }
    }

    @Test
    fun `forwards reset directly after binding`() = runTest {
        proxy.bind(mockClient)

        proxy.reset()

        coVerify { mockClient.reset() }
    }

    @Test
    fun `forwards setAdvertisingId directly after binding`() = runTest {
        proxy.bind(mockClient)

        proxy.setAdvertisingId("direct-gaid")

        verify { mockClient.setAdvertisingId("direct-gaid") }
    }

    @Test
    fun `forwards clearAdvertisingId directly after binding`() = runTest {
        proxy.bind(mockClient)

        proxy.clearAdvertisingId()

        verify { mockClient.clearAdvertisingId() }
    }

    @Test
    fun `forwards getDebugInfo to real client after binding`() = runTest {
        val expectedInfo = mapOf("lifecycle" to "ready", "queueLength" to 5)
        coEvery { mockClient.getDebugInfo() } returns expectedInfo

        proxy.bind(mockClient)
        val debugInfo = proxy.getDebugInfo()

        assertEquals(expectedInfo, debugInfo)
    }

    // ===== Queue Overflow =====

    @Test
    fun `drops oldest calls when queue overflows`() = runTest {
        val smallProxy = AnalyticsProxy(maxPendingCalls = 5)

        // Queue 7 events - first 2 should be dropped
        repeat(7) { i ->
            smallProxy.track("Event $i")
        }

        assertEquals(5, smallProxy.pendingCallCount())

        // Verify which events remain by binding and checking calls
        val callOrder = mutableListOf<String>()
        coEvery { mockClient.track(any(), any()) } answers {
            callOrder.add(firstArg<String>())
        }

        smallProxy.bind(mockClient)

        // Events 0 and 1 should have been dropped, 2-6 remain
        assertEquals(listOf("Event 2", "Event 3", "Event 4", "Event 5", "Event 6"), callOrder)
    }

    // ===== Bind Idempotency =====

    @Test
    fun `bind is idempotent - second bind is ignored`() = runTest {
        val secondClient: AnalyticsInterface = mockk(relaxed = true)

        proxy.track("Before Bind")
        proxy.bind(mockClient)

        // Second bind should be ignored
        proxy.bind(secondClient)

        proxy.track("After Bind")

        // First client should receive the call, not second
        verify { mockClient.track("After Bind", null) }
        verify(exactly = 0) { secondClient.track(any(), any()) }
    }

    // ===== Debug Info Before Binding =====

    @Test
    fun `getDebugInfo returns initializing state before binding`() = runTest {
        proxy.track("Pending Event")

        val debugInfo = proxy.getDebugInfo()

        assertEquals("initializing", debugInfo["lifecycle"])
        assertEquals(1, debugInfo["pendingCalls"])
        assertEquals(false, debugInfo["bound"])
    }

    // ===== Concurrent Operations =====

    @Test
    fun `handles concurrent queue operations safely`() = runTest {
        val results = (1..100).map { i ->
            async {
                proxy.track("Event $i")
            }
        }.awaitAll()

        assertEquals(100, proxy.pendingCallCount())
    }

    @Test
    fun `handles concurrent operations during binding`() = runTest {
        // Queue some initial events
        repeat(50) { i ->
            proxy.track("Pre-bind $i")
        }

        // Start binding while also queueing more events
        val bindJob = async { proxy.bind(mockClient) }
        val trackJobs = (1..50).map { i ->
            async { proxy.track("During-bind $i") }
        }

        bindJob.await()
        trackJobs.awaitAll()

        // All events should have been processed (either replayed or forwarded)
        assertTrue(proxy.isBound())
    }
}

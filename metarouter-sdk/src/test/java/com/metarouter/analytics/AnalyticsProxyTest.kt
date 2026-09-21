package com.metarouter.analytics

import com.metarouter.analytics.utils.Logger
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
        val clientInfo = mapOf("lifecycle" to "ready", "queueLength" to 5)
        coEvery { mockClient.getDebugInfo() } returns clientInfo

        proxy.bind(mockClient)
        val debugInfo = proxy.getDebugInfo()

        // Proxy adds "bound" to the client's debug info
        assertEquals(clientInfo + ("bound" to true), debugInfo)
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

    // ===== getAnonymousId =====

    @Test
    fun `getAnonymousId returns value after bind`() = runTest {
        coEvery { mockClient.getAnonymousId() } returns "anon-123"

        proxy.bind(mockClient)

        assertEquals("anon-123", proxy.getAnonymousId())
    }

    @Test
    fun `getAnonymousId suspends until bind completes`() = runTest {
        coEvery { mockClient.getAnonymousId() } returns "anon-456"

        val result = async { proxy.getAnonymousId() }

        // Give the coroutine a chance to start and suspend on the boundSignal.
        // It must not complete before bind() runs.
        delay(50)
        assertFalse("getAnonymousId should still be suspended before bind", result.isCompleted)

        proxy.bind(mockClient)

        assertEquals("anon-456", result.await())
    }

    @Test
    fun `getAnonymousId resolves degraded on a config-disabled session`() = runTest {
        proxy.markConfigDisabled("writeKey must not be empty or whitespace-only")

        // A refused session must resolve, not suspend forever — a permanent hang
        // would be a worse outcome than the crash the config gate replaces.
        assertEquals("", proxy.getAnonymousId())
    }

    @Test
    fun `clearConfigDisabled makes awaiters suspend for the incoming client`() = runTest {
        proxy.markConfigDisabled("writeKey must not be empty or whitespace-only")

        // A valid re-initialize clears the refusal before the new client is built.
        // Callers arriving in that pre-bind window must wait for the incoming bind,
        // not resolve degraded against the previous session's verdict.
        proxy.clearConfigDisabled()

        val result = async { proxy.getAnonymousId() }
        delay(50)
        assertFalse("must suspend for the incoming bind, not return degraded", result.isCompleted)

        coEvery { mockClient.getAnonymousId() } returns "anon-recovered"
        proxy.bind(mockClient)

        assertEquals("anon-recovered", result.await())
    }

    @Test
    fun `getDebugInfo carries the config error on a refused session`() = runTest {
        proxy.markConfigDisabled("writeKey must not be empty or whitespace-only")

        val info = proxy.getDebugInfo()

        assertEquals("disabled", info["lifecycle"])
        assertEquals(false, info["bound"])
        assertEquals("writeKey must not be empty or whitespace-only", info["configError"])
    }

    @Test
    fun `getAnonymousId returns stable value across calls`() = runTest {
        coEvery { mockClient.getAnonymousId() } returns "anon-stable"

        proxy.bind(mockClient)

        val id1 = proxy.getAnonymousId()
        val id2 = proxy.getAnonymousId()
        assertEquals(id1, id2)
    }

    @Test
    fun `getAnonymousId hangs after unbind until next bind`() = runTest {
        coEvery { mockClient.getAnonymousId() } returns "anon-first"
        proxy.bind(mockClient)
        assertEquals("anon-first", proxy.getAnonymousId())

        proxy.unbind()

        val result = async { proxy.getAnonymousId() }
        val pollResult = withTimeoutOrNull(100) { result.await() }
        assertNull("getAnonymousId should suspend while unbound", pollResult)

        val secondClient = mockk<AnalyticsInterface>(relaxed = true)
        coEvery { secondClient.getAnonymousId() } returns "anon-second"
        proxy.bind(secondClient)

        assertEquals("anon-second", result.await())
    }

    /**
     * markConfigDisabled completes the bound signal; before the waiter reads
     * the flag, a following valid init's clearConfigDisabled swaps flag and
     * signal. The waiter used to wake into client-null / flag-false and throw
     * IllegalStateException — it must recognize the swapped signal as "a new
     * bind is incoming" and await it instead.
     */
    @Test
    fun `waiter woken by a refusal that is immediately cleared awaits the incoming bind`() = runTest {
        repeat(50) { iteration ->
            val freshProxy = AnalyticsProxy()
            val waiter = async { freshProxy.getAnonymousId() }

            freshProxy.markConfigDisabled("bad config")
            freshProxy.clearConfigDisabled()

            val recovered = mockk<AnalyticsInterface>(relaxed = true)
            coEvery { recovered.getAnonymousId() } returns "anon-recovered"
            freshProxy.bind(recovered)

            // "" is legal only if the waiter read the flag before the clear;
            // an IllegalStateException is the regression this test pins.
            val result = waiter.await()
            assertTrue(
                "iteration $iteration got: $result",
                result == "" || result == "anon-recovered"
            )
        }
    }

    /**
     * A waiter parked BEFORE a teardown must not be stranded on the abandoned
     * signal: unbind() completes the outgoing signal last, waking the waiter
     * into the re-await loop, which resolves it against whatever the session
     * becomes — here, the refusal that follows (createAnalyticsClient(invalid)
     * parks waiters exactly like this: flags set synchronously, disable queued).
     */
    @Test
    fun `waiter parked before unbind resolves against the refusal that follows`() = runTest {
        val waiter = async { proxy.getAnonymousId() }
        delay(1) // let the waiter park on the current signal

        proxy.unbind()
        proxy.markConfigDisabled("bad config")

        assertEquals("", waiter.await())
    }

    /**
     * clearConfigDisabled must leave a fresh, INCOMPLETE signal behind: a new
     * waiter after the clear belongs to the incoming session and must suspend
     * for its bind — resolving degraded ("") against the cleared refusal, or
     * throwing, would answer with the previous session's verdict.
     */
    @Test
    fun `waiter arriving after a cleared refusal suspends for the incoming bind`() = runTest {
        proxy.markConfigDisabled("bad config")
        proxy.clearConfigDisabled()

        val waiter = async { proxy.getAnonymousId() }
        delay(1)
        assertFalse("waiter must suspend for the incoming session", waiter.isCompleted)

        coEvery { mockClient.getAnonymousId() } returns "anon-recovered"
        proxy.bind(mockClient)
        assertEquals("anon-recovered", waiter.await())
    }

    /** Same parked waiter, but the session recovers: it must get the NEW session's id. */
    @Test
    fun `waiter parked before unbind resolves against the next bind`() = runTest {
        val waiter = async { proxy.getAnonymousId() }
        delay(1)

        proxy.unbind()
        coEvery { mockClient.getAnonymousId() } returns "anon-after-reset"
        proxy.bind(mockClient)

        assertEquals("anon-after-reset", waiter.await())
    }
}

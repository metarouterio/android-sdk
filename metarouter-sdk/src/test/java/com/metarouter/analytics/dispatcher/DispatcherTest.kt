package com.metarouter.analytics.dispatcher

import android.util.Log
import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.network.CircuitBreaker
import com.metarouter.analytics.network.FakeNetworkClient
import com.metarouter.analytics.network.NetworkClient
import com.metarouter.analytics.network.NetworkResponse
import com.metarouter.analytics.queue.EventQueue
import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.types.EventContext
import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.LibraryContext
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherTest {

    private lateinit var queue: EventQueue
    private lateinit var networkClient: FakeNetworkClient
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var options: InitOptions
    private lateinit var config: DispatcherConfig

    @Before
    fun setup() {
        // Mock Android Log class
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        queue = EventQueue(maxCapacity = 100)
        networkClient = FakeNetworkClient()
        circuitBreaker = CircuitBreaker()
        options = InitOptions(
            writeKey = "test-write-key",
            ingestionHost = "https://api.example.com",
            flushIntervalSeconds = 10
        )
        config = DispatcherConfig(
            autoFlushThreshold = 5,
            initialMaxBatchSize = 10,
            timeoutMs = 5000,
            endpointPath = "/v1/batch"
        )
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    // ===== Batch Processing Tests =====

    @Test
    fun `flush drains correct batch size`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)

        repeat(15) { i ->
            queue.enqueue(createEvent("msg-$i"))
        }

        dispatcher.flush()

        // Should drain in batches of 10 (maxBatchSize), then 5
        assertEquals(2, networkClient.requests.size)
        assertEquals(0, queue.size())
    }

    @Test
    fun `flush adds sentAt timestamp to all events`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))
        queue.enqueue(createEvent("msg-2"))

        dispatcher.flush()

        val body = String(networkClient.requests[0].body, Charsets.UTF_8)
        assertTrue("Body should contain sentAt", body.contains("sentAt"))
        assertTrue("Body should contain batch array", body.contains("\"batch\""))
    }

    @Test
    fun `flush sends to correct endpoint`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()

        assertEquals(1, networkClient.requests.size)
        assertEquals("https://api.example.com/v1/batch", networkClient.requests[0].url)
    }

    @Test
    fun `flush with empty queue does nothing`() = runTest {
        val dispatcher = createDispatcher()

        dispatcher.flush()

        assertEquals(0, networkClient.requests.size)
    }

    // ===== Auto-flush Tests =====

    @Test
    fun `offer triggers auto-flush when threshold reached`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)

        repeat(5) { i ->
            dispatcher.offer(createEvent("msg-$i"))
        }

        assertEquals(1, networkClient.requests.size)
        assertEquals(0, queue.size())
    }

    @Test
    fun `offer does not trigger flush below threshold`() = runTest {
        val dispatcher = createDispatcher()

        repeat(4) { i ->
            dispatcher.offer(createEvent("msg-$i"))
        }

        assertEquals(0, networkClient.requests.size)
        assertEquals(4, queue.size())
    }

    // ===== HTTP Response Handling Tests =====

    @Test
    fun `200 response marks success and continues`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()

        assertEquals(1, networkClient.requests.size)
        assertEquals(0, queue.size())
    }

    @Test
    fun `500 response requeues batch`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(500, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))
        queue.enqueue(createEvent("msg-2"))

        dispatcher.flush()

        assertEquals(1, networkClient.requests.size)
        assertEquals(2, queue.size())  // Events requeued
        dispatcher.stop()  // Cancel pending retry job
    }

    @Test
    fun `408 response requeues batch`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(408, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()

        assertEquals(1, networkClient.requests.size)
        assertEquals(1, queue.size())
        dispatcher.stop()  // Cancel pending retry job
    }

    @Test
    fun `429 response requeues batch`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(429, mapOf("Retry-After" to "5"), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()

        assertEquals(1, queue.size())
        dispatcher.stop()  // Cancel pending retry job
    }

    @Test
    fun `413 response halves batch size`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(413, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()

        val debugInfo = dispatcher.getDebugInfo()
        assertEquals(5, debugInfo.maxBatchSize)  // 10 / 2 = 5
        dispatcher.stop()  // Cancel pending retry job
    }

    @Test
    fun `413 at batchSize 1 drops event`() = runTest {
        val smallConfig = DispatcherConfig(
            autoFlushThreshold = 5,
            initialMaxBatchSize = 1,
            timeoutMs = 5000,
            endpointPath = "/v1/batch"
        )
        val dispatcher = Dispatcher(
            options = options,
            queue = queue,
            networkClient = networkClient,
            circuitBreaker = circuitBreaker,
            scope = this,
            config = smallConfig
        )

        networkClient.nextResponse = NetworkResponse(413, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()

        assertEquals(0, queue.size())  // Event dropped
    }

    @Test
    fun `401 response clears queue and invokes callback`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(401, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))
        queue.enqueue(createEvent("msg-2"))

        var callbackCode: Int? = null
        dispatcher.onFatalConfigError = { code -> callbackCode = code }

        dispatcher.flush()

        assertEquals(0, queue.size())
        assertEquals(401, callbackCode)
    }

    @Test
    fun `403 response clears queue and invokes callback`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(403, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        var callbackCode: Int? = null
        dispatcher.onFatalConfigError = { code -> callbackCode = code }

        dispatcher.flush()

        assertEquals(0, queue.size())
        assertEquals(403, callbackCode)
    }

    @Test
    fun `404 response clears queue and invokes callback`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(404, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        var callbackCode: Int? = null
        dispatcher.onFatalConfigError = { code -> callbackCode = code }

        dispatcher.flush()

        assertEquals(0, queue.size())
        assertEquals(404, callbackCode)
    }

    @Test
    fun `400 response drops batch and continues`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(400, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()

        assertEquals(0, queue.size())
        assertEquals(1, networkClient.requests.size)
    }

    // ===== Tracing Tests =====

    @Test
    fun `tracing adds header when enabled`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))
        dispatcher.setTracing(true)

        dispatcher.flush()

        val request = networkClient.requests[0]
        assertEquals("true", request.additionalHeaders?.get("Trace"))
    }

    @Test
    fun `tracing header not present when disabled`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))
        dispatcher.setTracing(false)

        dispatcher.flush()

        val request = networkClient.requests[0]
        assertTrue(request.additionalHeaders == null || request.additionalHeaders.isEmpty())
    }

    // ===== Debug Info Tests =====

    @Test
    fun `getDebugInfo returns correct initial state`() = runTest {
        val dispatcher = createDispatcher()

        val info = dispatcher.getDebugInfo()

        assertFalse(info.isRunning)
        assertEquals(10, info.maxBatchSize)
        assertFalse(info.pendingRetry)
        assertFalse(info.tracingEnabled)
    }

    @Test
    fun `getDebugInfo reflects tracing state`() = runTest {
        val dispatcher = createDispatcher()
        dispatcher.setTracing(true)

        val info = dispatcher.getDebugInfo()

        assertTrue(info.tracingEnabled)
    }

    // ===== Network Error Tests =====

    @Test
    fun `network error requeues batch`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextException = java.io.IOException("Connection refused")
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()

        assertEquals(1, queue.size())
        dispatcher.stop()  // Cancel pending retry job
    }

    // ===== Lifecycle Tests =====

    @Test
    fun `start sets running state`() = runTest {
        val dispatcher = createDispatcher()

        dispatcher.start()

        assertTrue(dispatcher.getDebugInfo().isRunning)
        dispatcher.stop()
    }

    @Test
    fun `stop clears running state`() = runTest {
        val dispatcher = createDispatcher()
        dispatcher.start()

        dispatcher.stop()

        assertFalse(dispatcher.getDebugInfo().isRunning)
    }

    // ===== Periodic Flush Tests =====

    @Test
    fun `periodic flush triggers after interval`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.start()

        // Advance time past flush interval (10 seconds)
        testScheduler.advanceTimeBy(11_000)
        testScheduler.runCurrent()

        assertTrue("Should have made at least one request", networkClient.requests.size >= 1)
        dispatcher.stop()
    }

    // ===== Concurrent Flush Tests =====

    @Test
    fun `concurrent flush attempts are prevented by mutex`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)

        // Enqueue enough events for multiple batches
        repeat(25) { i ->
            queue.enqueue(createEvent("msg-$i"))
        }

        // Launch multiple concurrent flushes
        val flushJobs = (1..5).map {
            async { dispatcher.flush() }
        }
        flushJobs.awaitAll()

        // Should process all events in batches (25 events / 10 batch size = 3 batches)
        // But concurrent flushes should be skipped, so we expect exactly the right number
        // of requests to process all events without duplication
        assertEquals(0, queue.size()) // All events should be processed
        assertEquals(3, networkClient.requests.size) // 3 batches of 10, 10, 5
    }

    // ===== Helper Methods =====

    private fun TestScope.createDispatcher(): Dispatcher {
        return Dispatcher(
            options = options,
            queue = queue,
            networkClient = networkClient,
            circuitBreaker = circuitBreaker,
            scope = this,
            config = config
        )
    }

    private fun createEvent(messageId: String): EnrichedEventPayload {
        return EnrichedEventPayload(
            type = EventType.TRACK,
            event = "Test Event",
            userId = "test-user",
            anonymousId = "test-anon",
            groupId = null,
            traits = null,
            properties = null,
            timestamp = "2024-01-01T00:00:00.000Z",
            context = EventContext(
                app = null,
                device = null,
                library = LibraryContext(name = "test-sdk", version = "1.0.0"),
                locale = null,
                network = null,
                os = null,
                screen = null,
                timezone = null
            ),
            messageId = messageId,
            writeKey = "test-key",
            sentAt = null
        )
    }
}

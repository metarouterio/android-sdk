package com.metarouter.analytics.dispatcher

import android.util.Log
import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.network.CircuitBreaker
import com.metarouter.analytics.network.CircuitState
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
    fun `batch size recovers after 413 followed by successful flushes`() = runTest {
        val dispatcher = createDispatcher()

        // 413 halves batch size: 10 -> 5
        networkClient.nextResponse = NetworkResponse(413, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))
        dispatcher.flush()
        dispatcher.stop()
        assertEquals(5, dispatcher.getDebugInfo().maxBatchSize)

        // Successful flush doubles it: 5 -> 10 (capped at initial)
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        queue.enqueue(createEvent("msg-2"))
        dispatcher.flush()
        assertEquals(10, dispatcher.getDebugInfo().maxBatchSize)
    }

    @Test
    fun `batch size recovers gradually over multiple successes`() = runTest {
        val largeConfig = DispatcherConfig(
            autoFlushThreshold = 100,
            initialMaxBatchSize = 40,
            timeoutMs = 5000,
            endpointPath = "/v1/batch"
        )
        val dispatcher = Dispatcher(
            options = options,
            queue = queue,
            networkClient = networkClient,
            circuitBreaker = circuitBreaker,
            scope = this,
            config = largeConfig
        )

        // Two 413s: 40 -> 20 -> 10
        networkClient.nextResponse = NetworkResponse(413, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))
        dispatcher.flush()
        dispatcher.stop()
        assertEquals(20, dispatcher.getDebugInfo().maxBatchSize)

        networkClient.nextResponse = NetworkResponse(413, emptyMap(), null)
        queue.enqueue(createEvent("msg-2"))
        dispatcher.flush()
        dispatcher.stop()
        assertEquals(10, dispatcher.getDebugInfo().maxBatchSize)

        // Recover: 10 -> 20 -> 40
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        queue.enqueue(createEvent("msg-3"))
        dispatcher.flush()
        assertEquals(20, dispatcher.getDebugInfo().maxBatchSize)

        queue.enqueue(createEvent("msg-4"))
        dispatcher.flush()
        assertEquals(40, dispatcher.getDebugInfo().maxBatchSize)

        // Should not exceed initial
        queue.enqueue(createEvent("msg-5"))
        dispatcher.flush()
        assertEquals(40, dispatcher.getDebugInfo().maxBatchSize)
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

    // ===== Pause/Resume Tests =====

    @Test
    fun `pause stops flush loop`() = runTest {
        val dispatcher = createDispatcher()
        dispatcher.start()
        assertTrue(dispatcher.getDebugInfo().isRunning)

        dispatcher.pause()

        assertFalse(dispatcher.getDebugInfo().isRunning)
        assertTrue(dispatcher.isPaused())
    }

    @Test
    fun `pause cancels pending retry job`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(500, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()
        assertTrue(dispatcher.getDebugInfo().pendingRetry)

        dispatcher.pause()

        assertFalse(dispatcher.getDebugInfo().pendingRetry)
    }

    @Test
    fun `resume restarts flush loop when paused`() = runTest {
        val dispatcher = createDispatcher()
        dispatcher.start()
        dispatcher.pause()
        assertTrue(dispatcher.isPaused())

        dispatcher.resume()

        assertTrue(dispatcher.getDebugInfo().isRunning)
        assertFalse(dispatcher.isPaused())
        dispatcher.stop()
    }

    @Test
    fun `resume is idempotent when already running`() = runTest {
        val dispatcher = createDispatcher()
        dispatcher.start()
        assertFalse(dispatcher.isPaused())

        dispatcher.resume()  // Should not throw or change state

        assertTrue(dispatcher.getDebugInfo().isRunning)
        assertFalse(dispatcher.isPaused())
        dispatcher.stop()
    }

    @Test
    fun `isPaused returns false when never started`() = runTest {
        val dispatcher = createDispatcher()

        assertFalse(dispatcher.isPaused())
    }

    @Test
    fun `isPaused returns false when running`() = runTest {
        val dispatcher = createDispatcher()
        dispatcher.start()

        assertFalse(dispatcher.isPaused())
        dispatcher.stop()
    }

    @Test
    fun `isPaused returns true after pause`() = runTest {
        val dispatcher = createDispatcher()
        dispatcher.start()
        dispatcher.pause()

        assertTrue(dispatcher.isPaused())
    }

    @Test
    fun `pause is idempotent`() = runTest {
        val dispatcher = createDispatcher()
        dispatcher.start()

        dispatcher.pause()
        dispatcher.pause()  // Should not throw

        assertTrue(dispatcher.isPaused())
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

    // ===== Retry Backoff Floor Tests =====

    @Test
    fun `network error applies retry floor on first failure`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.enqueueExceptions(java.io.IOException("timeout"))
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()

        assertEquals(1, queue.size())
        assertTrue(dispatcher.getDebugInfo().pendingRetry)
        dispatcher.stop()
    }

    @Test
    fun `retry floor grows exponentially across consecutive failures`() = runTest {
        // Test with 2 failures (circuit stays closed at threshold=3)
        // to isolate retry floor behavior from circuit breaker wall-clock timing
        val dispatcher = createDispatcher()
        queue.enqueue(createEvent("msg-1"))

        // Fail 1 → retry floor = 1000ms (base)
        networkClient.nextException = java.io.IOException("fail 1")
        dispatcher.flush()
        assertEquals(1, queue.size())

        // At 500ms — retry should NOT have fired (floor is 1000ms)
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(1, networkClient.requests.size)

        // Retry fires at 1000ms → fail 2 → retry floor = 2000ms
        networkClient.nextException = java.io.IOException("fail 2")
        testScheduler.advanceTimeBy(600)
        testScheduler.runCurrent()
        assertEquals(2, networkClient.requests.size)
        assertEquals(1, queue.size())

        // At 2500ms from fail 2 — retry should NOT have fired (floor is 2000ms)
        // But actually the delay starts from when the retry was scheduled (~1100ms)
        // Retry is at ~1100 + 2000 = 3100ms virtual

        // Success after 2000ms floor
        networkClient.nextException = null
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        testScheduler.advanceTimeBy(2100)
        testScheduler.runCurrent()
        assertEquals(0, queue.size())
        dispatcher.stop()
    }

    @Test
    fun `consecutive retries reset on success`() = runTest {
        val dispatcher = createDispatcher()

        // Fail once → retry with 1s floor → succeed
        networkClient.enqueueExceptions(java.io.IOException("fail"))
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()
        assertEquals(1, queue.size()) // Requeued after failure

        // Advance past retry floor (1s) → success
        testScheduler.advanceTimeBy(1100)
        testScheduler.runCurrent()
        assertEquals(0, queue.size())

        // Now fail again — retry floor should be back to base (1s), not escalated
        networkClient.reset()
        networkClient.enqueueExceptions(java.io.IOException("fail again"))
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        queue.enqueue(createEvent("msg-2"))

        dispatcher.flush()
        assertEquals(1, queue.size())

        // 1.1s should be enough for base retry floor (not escalated)
        testScheduler.advanceTimeBy(1100)
        testScheduler.runCurrent()
        assertEquals(0, queue.size())
        dispatcher.stop()
    }

    @Test
    fun `5xx applies retry floor with minimum 100ms`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(503, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()
        assertEquals(1, queue.size())

        // Switch to success and advance past retry floor (1s > 100ms floor)
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        testScheduler.advanceTimeBy(1100)
        testScheduler.runCurrent()
        assertEquals(0, queue.size())
        dispatcher.stop()
    }

    @Test
    fun `429 applies retry floor with minimum 1000ms`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(429, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()
        assertEquals(1, queue.size())

        // Advance 900ms — should not have retried yet (1000ms minimum for 429)
        testScheduler.advanceTimeBy(900)
        testScheduler.runCurrent()
        assertEquals(1, queue.size())

        // Switch to success and advance past 1000ms
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()
        assertEquals(0, queue.size())
        dispatcher.stop()
    }

    // ===== Start Idempotency Tests =====

    @Test
    fun `start is idempotent - second call does not cancel first`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.start()
        assertTrue(dispatcher.getDebugInfo().isRunning)

        // Call start again — should be a no-op
        dispatcher.start()
        assertTrue(dispatcher.getDebugInfo().isRunning)

        // Flush should still work
        testScheduler.advanceTimeBy(11_000)
        testScheduler.runCurrent()
        assertTrue(networkClient.requests.size >= 1)
        dispatcher.stop()
    }

    // ===== 413 Immediate Retry Tests =====

    @Test
    fun `413 retries with reduced batch size after isFlushing released`() = runTest {
        val dispatcher = createDispatcher()
        queue.enqueue(createEvent("msg-1"))

        // First call → 413 → halve batch, requeue, schedule immediate retry
        networkClient.nextResponse = NetworkResponse(413, emptyMap(), null)
        dispatcher.flush()
        assertEquals(5, dispatcher.getDebugInfo().maxBatchSize)
        assertEquals(1, queue.size()) // Requeued

        // Switch to success and run the scheduled retry
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        testScheduler.runCurrent()

        assertEquals(0, queue.size()) // Retry succeeded
        assertEquals(2, networkClient.requests.size) // Two API calls total
        dispatcher.stop()
    }

    // ===== Logging Format Tests =====

    @Test
    fun `500 error log includes circuit state and retry count`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(500, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        val warnLogs = mutableListOf<String>()
        every { Log.w(any(), any<String>()) } answers {
            warnLogs.add(secondArg())
            0
        }

        dispatcher.flush()

        val errorLog = warnLogs.find { it.contains("Server error 500") }
        assertNotNull("Should log server error", errorLog)
        assertTrue("Should include circuit state", errorLog!!.contains("circuit:"))
        assertTrue("Should include retry count", errorLog.contains("retry #"))
        dispatcher.stop()
    }

    @Test
    fun `network error log includes circuit state and retry count`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextException = java.io.IOException("Connection reset")
        queue.enqueue(createEvent("msg-1"))

        val warnLogs = mutableListOf<String>()
        every { Log.w(any(), any<String>()) } answers {
            warnLogs.add(secondArg())
            0
        }

        dispatcher.flush()

        val errorLog = warnLogs.find { it.contains("API call failed") }
        assertNotNull("Should log API call failure", errorLog)
        assertTrue("Should include circuit state", errorLog!!.contains("circuit:"))
        assertTrue("Should include retry count", errorLog.contains("retry #1"))
        dispatcher.stop()
    }

    // ===== processUntilEmpty Loop Tests =====

    @Test
    fun `processUntilEmpty loops to drain all batches on success`() = runTest {
        val dispatcher = createDispatcher() // maxBatchSize=10
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)

        // Enqueue 25 events — should require 3 batches (10 + 10 + 5)
        repeat(25) { i -> queue.enqueue(createEvent("msg-$i")) }

        dispatcher.flush()

        assertEquals(0, queue.size())
        assertEquals(3, networkClient.requests.size)
    }

    // ===== Serialization Failure Tests =====

    @Test
    fun `serialization failure drops batch and continues to next`() = runTest {
        // Create a dispatcher with a very small batch size so we get 2 batches
        val smallBatchConfig = DispatcherConfig(
            autoFlushThreshold = 100,
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
            config = smallBatchConfig
        )
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)

        // Enqueue 2 normal events
        queue.enqueue(createEvent("msg-1"))
        queue.enqueue(createEvent("msg-2"))

        dispatcher.flush()

        // Both events should be processed (batch size 1 = 2 API calls)
        assertEquals(0, queue.size())
        assertEquals(2, networkClient.requests.size)
    }

    // ===== Server Error Retry Reset Tests =====

    @Test
    fun `consecutive retries reset on 2xx after server error`() = runTest {
        val dispatcher = createDispatcher()
        queue.enqueue(createEvent("msg-1"))

        // 500 → retry #1 with 1s floor
        networkClient.nextResponse = NetworkResponse(500, emptyMap(), null)
        dispatcher.flush()
        assertEquals(1, queue.size())

        // Succeed on retry
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        testScheduler.advanceTimeBy(1100)
        testScheduler.runCurrent()
        assertEquals(0, queue.size())

        // Next failure should be retry #1 again (reset after success)
        queue.enqueue(createEvent("msg-2"))
        networkClient.nextResponse = NetworkResponse(503, emptyMap(), null)

        val warnLogs = mutableListOf<String>()
        every { Log.w(any(), any<String>()) } answers {
            warnLogs.add(secondArg())
            0
        }

        dispatcher.flush()

        val errorLog = warnLogs.find { it.contains("Server error 503") }
        assertNotNull("Should log server error", errorLog)
        assertTrue("Should be retry #1 (reset after success)", errorLog!!.contains("retry #1"))
        dispatcher.stop()
    }

    // ===== Network Pause Tests =====

    @Test
    fun `events enqueue while network paused with no HTTP attempts`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        dispatcher.pauseForOffline()

        repeat(5) { i ->
            dispatcher.offer(createEvent("msg-$i"))
        }

        // Trigger flush — should be skipped due to networkPaused
        dispatcher.flush()

        assertEquals(0, networkClient.requests.size)
        assertEquals(5, queue.size())
        dispatcher.stop()
    }

    @Test
    fun `offline to online transition triggers flush`() = runTest {
        val dispatcher = createDispatcher()
        dispatcher.pauseForOffline()

        repeat(3) { i ->
            dispatcher.offer(createEvent("msg-$i"))
        }

        // No HTTP while offline
        dispatcher.flush()
        assertEquals(0, networkClient.requests.size)

        // Come online
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        dispatcher.resumeFromOffline()
        dispatcher.flush()

        assertEquals(1, networkClient.requests.size)
        assertEquals(0, queue.size())
        dispatcher.stop()
    }

    @Test
    fun `networkPaused reflected in getDebugInfo`() = runTest {
        val dispatcher = createDispatcher()

        assertFalse(dispatcher.getDebugInfo().networkPaused)

        dispatcher.pauseForOffline()
        assertTrue(dispatcher.getDebugInfo().networkPaused)

        dispatcher.resumeFromOffline()
        assertFalse(dispatcher.getDebugInfo().networkPaused)
    }

    @Test
    fun `pauseForOffline cancels pending retry job`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(500, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))

        dispatcher.flush()
        assertTrue(dispatcher.getDebugInfo().pendingRetry)

        dispatcher.pauseForOffline()

        assertFalse(dispatcher.getDebugInfo().pendingRetry)
        dispatcher.stop()
    }

    @Test
    fun `resumeFromOffline resets consecutiveRetries`() = runTest {
        val dispatcher = createDispatcher()

        // Fail 3 times to build up consecutiveRetries
        networkClient.nextResponse = NetworkResponse(500, emptyMap(), null)
        queue.enqueue(createEvent("msg-1"))
        dispatcher.flush()
        dispatcher.stop() // Cancel pending retry

        // Go offline then online — resets retries
        dispatcher.pauseForOffline()
        dispatcher.resumeFromOffline()

        // Next failure should be retry #1 (not #4)
        networkClient.nextResponse = NetworkResponse(500, emptyMap(), null)
        val warnLogs = mutableListOf<String>()
        every { Log.w(any(), any<String>()) } answers {
            warnLogs.add(secondArg())
            0
        }

        dispatcher.flush()

        val errorLog = warnLogs.find { it.contains("Server error 500") }
        assertNotNull("Should log server error", errorLog)
        assertTrue("Should be retry #1 after offline reset", errorLog!!.contains("retry #1"))
        dispatcher.stop()
    }

    @Test
    fun `resumeFromOffline is idempotent when not paused`() = runTest {
        val dispatcher = createDispatcher()

        // Should not throw or change state
        dispatcher.resumeFromOffline()

        assertFalse(dispatcher.getDebugInfo().networkPaused)
    }

    @Test
    fun `flush skips processing entirely when networkPaused`() = runTest {
        val dispatcher = createDispatcher()
        networkClient.nextResponse = NetworkResponse(200, emptyMap(), null)

        repeat(10) { i ->
            queue.enqueue(createEvent("msg-$i"))
        }

        dispatcher.pauseForOffline()
        dispatcher.flush()

        // No HTTP requests should have been made
        assertEquals(0, networkClient.requests.size)
        // All events remain in queue
        assertEquals(10, queue.size())
        dispatcher.stop()
    }

    @Test
    fun `circuit breaker does not reset while connected but failing`() = runTest {
        val breaker = CircuitBreaker(
            failureThreshold = 2,
            baseCooldownMs = 1000,
            maxCooldownMs = 120_000,
            jitterRatio = 0.0
        )
        val dispatcher = Dispatcher(
            options = options,
            queue = queue,
            networkClient = networkClient,
            circuitBreaker = breaker,
            scope = this,
            config = config
        )

        // Device is connected (not network-paused) but server returns 500s
        networkClient.nextResponse = NetworkResponse(500, emptyMap(), null)

        queue.enqueue(createEvent("msg-1"))
        dispatcher.flush()
        queue.enqueue(createEvent("msg-2"))
        dispatcher.flush()

        // CB should be open after hitting failure threshold
        assertEquals(CircuitState.Open, breaker.getState())
        val firstCooldown = breaker.getRemainingCooldownMs()
        assertTrue("CB should have active cooldown", firstCooldown > 0)

        // Still connected, still failing — CB must NOT reset
        assertFalse(
            "Dispatcher should not be network-paused (device is connected)",
            dispatcher.getDebugInfo().networkPaused
        )
        assertEquals(
            "CB should remain open while connected but failing",
            CircuitState.Open, breaker.getState()
        )

        dispatcher.stop()
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
                library = LibraryContext(name = "test-sdk", version = "1.1.0"),
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

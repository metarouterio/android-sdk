package com.metarouter.analytics

import android.content.Context
import com.metarouter.analytics.queue.EventQueueInterface
import com.metarouter.analytics.session.SessionManager
import com.metarouter.analytics.session.SessionStorage
import com.metarouter.analytics.types.EnrichedEventPayload
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end session behavior through a real client: the optional
 * `Session Started` event, its gating, and the `getSessionId()` surface.
 * Lifecycle events stay off so the only session activity is the explicit
 * calls each test makes.
 */
@RunWith(RobolectricTestRunner::class)
class SessionTrackingClientTest {

    private lateinit var context: Context

    @Volatile private var wallNow = 1_757_400_000_000L
    @Volatile private var monoNow = 50_000L

    /** Records enqueued events and never lets the dispatcher drain them away. */
    private class RecordingEventQueue : EventQueueInterface {
        val events = CopyOnWriteArrayList<EnrichedEventPayload>()
        override fun size(): Int = events.size
        override fun enqueue(event: EnrichedEventPayload) { events.add(event) }
        override fun drain(max: Int): List<EnrichedEventPayload> = emptyList()
        override fun requeueToFront(events: List<EnrichedEventPayload>) {}
        override fun clear() { events.clear() }
    }

    private lateinit var queue: RecordingEventQueue
    private lateinit var sessionManager: SessionManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        wallNow = 1_757_400_000_000L
        monoNow = 50_000L
        queue = RecordingEventQueue()

        val sessionStorage = mockk<SessionStorage>(relaxUnitFun = true) {
            every { getSessionId() } returns null
            every { getSessionCount() } returns null
            every { getLastActivityMs() } returns null
        }
        sessionManager = SessionManager(
            storage = sessionStorage,
            wallClockMillis = { wallNow },
            monotonicClockMillis = { monoNow }
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private suspend fun awaitCondition(timeoutMs: Long = 2000, condition: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            delay(50)
        }
    }

    private suspend fun makeClient(fireSessionStarted: Boolean): MetaRouterAnalyticsClient {
        val options = InitOptions(
            writeKey = "test-write-key",
            ingestionHost = "https://events.example.com",
            flushIntervalSeconds = 999,
            trackLifecycleEvents = false,
            fireSessionStarted = fireSessionStarted
        )
        return MetaRouterAnalyticsClient.initialize(
            context, options,
            eventQueue = queue,
            sessionManager = sessionManager
        )
    }

    private fun sessionIdOf(event: EnrichedEventPayload): JsonPrimitive? =
        event.context.providers?.get("metarouter")?.get("sessionID") as? JsonPrimitive

    @Test
    fun `first event mints and fires Session Started once`() = runBlocking {
        val client = makeClient(fireSessionStarted = true)

        client.track("First Event")
        client.track("Second Event")

        // Session Started rides the same FIFO channel; wait for all three
        // (2 tracked + 1 session start), then push a barrier event through the
        // full pipeline so any straggler duplicate would already have landed.
        awaitCondition { queue.events.size >= 3 }
        client.track("Barrier")
        awaitCondition { queue.events.any { it.event == "Barrier" } }

        val sessionStarts = queue.events.filter { it.event == "Session Started" }
        assertEquals("one session, one Session Started", 1, sessionStarts.size)
        assertEquals(
            "Session Started is stamped with the session it announces",
            JsonPrimitive("1757400000000"), sessionIdOf(sessionStarts[0])
        )
        assertTrue(queue.events.all { sessionIdOf(it) == JsonPrimitive("1757400000000") })
    }

    @Test
    fun `rollover fires Session Started for the new session`() = runBlocking {
        val client = makeClient(fireSessionStarted = true)

        client.track("Before Gap")
        awaitCondition { queue.events.size >= 2 }
        queue.clear()

        wallNow += 31 * 60_000L
        monoNow += 31 * 60_000L
        client.track("After Gap")
        awaitCondition { queue.events.size >= 2 }

        val newId = JsonPrimitive(wallNow.toString())
        val sessionStarts = queue.events.filter { it.event == "Session Started" }
        assertEquals("rollover mints once, announces once", 1, sessionStarts.size)
        assertEquals(newId, sessionIdOf(sessionStarts[0]))
        assertEquals(
            newId,
            queue.events.first { it.event == "After Gap" }.let { sessionIdOf(it) }
        )
    }

    @Test
    fun `no Session Started when flag off`() = runBlocking {
        val client = makeClient(fireSessionStarted = false)

        client.track("Only Event")
        awaitCondition { queue.events.size >= 1 }
        // Barrier through the full pipeline: a stray Session Started minted by
        // "Only Event" would ride the same channel and land before or with it.
        client.track("Barrier")
        awaitCondition { queue.events.any { it.event == "Barrier" } }

        assertFalse(
            "default is off — upgrading must not change event volume",
            queue.events.any { it.event == "Session Started" }
        )
        assertEquals(
            "the stamp itself is always on",
            JsonPrimitive("1757400000000"), sessionIdOf(queue.events[0])
        )
    }

    @Test
    fun `getSessionId is null before first event then matches the stamp`() = runBlocking {
        val client = makeClient(fireSessionStarted = false)

        assertNull("reads do not start sessions", client.getSessionId())

        client.track("First Event")
        awaitCondition { queue.events.size >= 1 }

        val sessionId = client.getSessionId()
        assertEquals("1757400000000", sessionId)
        assertEquals(
            "the getter reports the session events are stamped with",
            JsonPrimitive(sessionId!!), sessionIdOf(queue.events[0])
        )

        val debugInfo = client.getDebugInfo()
        assertEquals("1757400000000", debugInfo["sessionId"])
        assertEquals(1, debugInfo["sessionCount"])
    }

    /**
     * Session state deliberately survives reset() (its own prefs file, untouched
     * by identity clearing) — so the getter must keep reporting the surviving
     * session instead of inventing a boundary the event stream does not have.
     * Pins parity with iOS, whose getter has no lifecycle gate.
     */
    @Test
    fun `getSessionId survives reset`() = runBlocking {
        val client = makeClient(fireSessionStarted = false)

        client.track("First Event")
        awaitCondition { queue.events.size >= 1 }
        val before = client.getSessionId()

        client.reset()

        assertEquals(
            "post-reset diagnostics must see the surviving session",
            before, client.getSessionId()
        )
    }
}

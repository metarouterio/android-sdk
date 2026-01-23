package com.metarouter.analytics.queue

import com.metarouter.analytics.types.*
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class EventQueueTest {

    private lateinit var queue: EventQueue

    @Before
    fun setup() {
        queue = EventQueue(maxCapacity = 10)
    }

    @Test
    fun `queue starts empty`() {
        assertEquals(0, queue.size())
    }

    @Test
    fun `enqueue adds event to queue`() {
        queue.enqueue(createMockEvent("msg-1"))
        assertEquals(1, queue.size())
    }

    @Test
    fun `enqueue adds multiple events`() {
        repeat(5) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }
        assertEquals(5, queue.size())
    }

    @Test
    fun `enqueue drops oldest event when capacity exceeded`() {
        // Fill to capacity
        repeat(10) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }
        assertEquals(10, queue.size())

        // Add 11th event - should drop oldest, size stays at 10
        queue.enqueue(createMockEvent("msg-10"))
        assertEquals(10, queue.size())
    }

    @Test
    fun `enqueue continues dropping oldest as capacity is exceeded`() {
        repeat(10) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        // Add 5 more events
        repeat(5) { i ->
            queue.enqueue(createMockEvent("msg-${10 + i}"))
        }

        // Size should still be at capacity
        assertEquals(10, queue.size())
    }

    @Test
    fun `queue with capacity 1 keeps only latest event`() {
        val smallQueue = EventQueue(maxCapacity = 1)

        smallQueue.enqueue(createMockEvent("msg-1"))
        smallQueue.enqueue(createMockEvent("msg-2"))
        smallQueue.enqueue(createMockEvent("msg-3"))

        assertEquals(1, smallQueue.size())
    }

    @Test
    fun `clear removes all events`() {
        repeat(5) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        queue.clear()

        assertEquals(0, queue.size())
    }

    @Test
    fun `clear on empty queue does nothing`() {
        queue.clear()
        assertEquals(0, queue.size())
    }

    @Test
    fun `concurrent enqueue operations are thread-safe`() {
        val threadCount = 10
        val eventsPerThread = 100
        val latch = CountDownLatch(threadCount)

        val threads = (1..threadCount).map { threadId ->
            thread {
                repeat(eventsPerThread) { i ->
                    queue.enqueue(createMockEvent("thread-$threadId-event-$i"))
                }
                latch.countDown()
            }
        }

        latch.await()
        threads.forEach { it.join() }

        // Queue has max capacity of 10, should have exactly 10 events
        assertEquals(10, queue.size())
    }

    @Test
    fun `concurrent size checks are thread-safe`() {
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val sizes = mutableListOf<Int>()
        val sizeLock = Any()

        repeat(5) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        val threads = (1..threadCount).map {
            thread {
                repeat(100) {
                    val size = queue.size()
                    synchronized(sizeLock) {
                        sizes.add(size)
                    }
                }
                latch.countDown()
            }
        }

        latch.await()
        threads.forEach { it.join() }

        // All size checks should return valid values (0-10)
        assertTrue(sizes.all { it in 0..10 })
    }

    @Test
    fun `queue with large capacity works correctly`() {
        val largeQueue = EventQueue(maxCapacity = 10000)

        repeat(5000) { i ->
            largeQueue.enqueue(createMockEvent("msg-$i"))
        }

        assertEquals(5000, largeQueue.size())
    }

    // ===== Drain Tests =====

    @Test
    fun `drain removes and returns events from front`() {
        repeat(5) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        val drained = queue.drain(3)

        assertEquals(3, drained.size)
        assertEquals("msg-0", drained[0].messageId)
        assertEquals("msg-1", drained[1].messageId)
        assertEquals("msg-2", drained[2].messageId)
        assertEquals(2, queue.size())
    }

    @Test
    fun `drain returns all events when max exceeds queue size`() {
        repeat(3) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        val drained = queue.drain(10)

        assertEquals(3, drained.size)
        assertEquals(0, queue.size())
    }

    @Test
    fun `drain returns empty list when queue is empty`() {
        val drained = queue.drain(5)

        assertTrue(drained.isEmpty())
    }

    @Test
    fun `drain with zero max returns empty list`() {
        queue.enqueue(createMockEvent("msg-1"))

        val drained = queue.drain(0)

        assertTrue(drained.isEmpty())
        assertEquals(1, queue.size())
    }

    // ===== RequeueToFront Tests =====

    @Test
    fun `requeueToFront adds events to front in order`() {
        queue.enqueue(createMockEvent("existing-1"))
        queue.enqueue(createMockEvent("existing-2"))

        val eventsToRequeue = listOf(
            createMockEvent("requeue-1"),
            createMockEvent("requeue-2")
        )
        queue.requeueToFront(eventsToRequeue)

        // Drain all to verify order
        val all = queue.drain(10)
        assertEquals(4, all.size)
        assertEquals("requeue-1", all[0].messageId)
        assertEquals("requeue-2", all[1].messageId)
        assertEquals("existing-1", all[2].messageId)
        assertEquals("existing-2", all[3].messageId)
    }

    @Test
    fun `requeueToFront to empty queue works`() {
        val events = listOf(
            createMockEvent("msg-1"),
            createMockEvent("msg-2")
        )
        queue.requeueToFront(events)

        assertEquals(2, queue.size())
        val drained = queue.drain(10)
        assertEquals("msg-1", drained[0].messageId)
        assertEquals("msg-2", drained[1].messageId)
    }

    @Test
    fun `requeueToFront with empty list does nothing`() {
        queue.enqueue(createMockEvent("msg-1"))

        queue.requeueToFront(emptyList())

        assertEquals(1, queue.size())
    }

    @Test
    fun `drain and requeueToFront roundtrip preserves events`() {
        repeat(5) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        val drained = queue.drain(3)
        queue.requeueToFront(drained)

        assertEquals(5, queue.size())
        val all = queue.drain(10)
        assertEquals("msg-0", all[0].messageId)
        assertEquals("msg-1", all[1].messageId)
        assertEquals("msg-2", all[2].messageId)
        assertEquals("msg-3", all[3].messageId)
        assertEquals("msg-4", all[4].messageId)
    }

    private fun createMockEvent(messageId: String): EnrichedEventPayload {
        return EnrichedEventPayload(
            type = EventType.TRACK,
            event = "Test Event",
            userId = "test-user",
            anonymousId = "test-anon",
            groupId = null,
            traits = null,
            properties = null,
            timestamp = "2024-01-01T00:00:00.000Z",
            context = mockk(relaxed = true),
            messageId = messageId,
            writeKey = "test-key",
            sentAt = null
        )
    }
}

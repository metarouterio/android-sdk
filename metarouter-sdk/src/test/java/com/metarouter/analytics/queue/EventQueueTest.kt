package com.metarouter.analytics.queue

import com.metarouter.analytics.types.*
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class EventQueueTest {

    private lateinit var queue: EventQueue

    @Before
    fun setup() {
        queue = EventQueue(maxCapacity = 10)
    }

    // ===== Basic Operations =====

    @Test
    fun `queue starts empty`() {
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
    }

    @Test
    fun `enqueue adds event to queue`() {
        val event = createMockEvent("msg-1")

        queue.enqueue(event)

        assertEquals(1, queue.size())
        assertFalse(queue.isEmpty())
    }

    @Test
    fun `enqueue adds multiple events`() {
        repeat(5) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        assertEquals(5, queue.size())
    }

    @Test
    fun `drain removes events in FIFO order`() {
        val events = (1..5).map { createMockEvent("msg-$it") }
        events.forEach { queue.enqueue(it) }

        val drained = queue.drain(5)

        assertEquals(5, drained.size)
        assertEquals(events.map { it.messageId }, drained.map { it.messageId })
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `drain respects maxBatchSize`() {
        repeat(10) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        val drained = queue.drain(3)

        assertEquals(3, drained.size)
        assertEquals(7, queue.size())
    }

    @Test
    fun `drain returns empty list when queue is empty`() {
        val drained = queue.drain(10)

        assertTrue(drained.isEmpty())
    }

    @Test
    fun `drain with zero maxBatchSize returns empty list`() {
        queue.enqueue(createMockEvent("msg-1"))

        val drained = queue.drain(0)

        assertTrue(drained.isEmpty())
        assertEquals(1, queue.size())
    }

    @Test
    fun `peek returns next event without removing it`() {
        val event1 = createMockEvent("msg-1")
        val event2 = createMockEvent("msg-2")
        queue.enqueue(event1)
        queue.enqueue(event2)

        val peeked = queue.peek()

        assertEquals(event1.messageId, peeked?.messageId)
        assertEquals(2, queue.size()) // Size unchanged
    }

    @Test
    fun `peek returns null when queue is empty`() {
        val peeked = queue.peek()

        assertNull(peeked)
    }

    @Test
    fun `clear removes all events`() {
        repeat(5) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        queue.clear()

        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
    }

    // ===== Overflow Handling =====

    @Test
    fun `enqueue drops oldest event when capacity exceeded`() {
        // Max capacity is 10
        repeat(10) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }
        assertEquals(10, queue.size())

        // Add 11th event - should drop msg-0 (oldest)
        queue.enqueue(createMockEvent("msg-10"))

        assertEquals(10, queue.size())
        val drained = queue.drain(10)
        assertEquals("msg-1", drained.first().messageId) // msg-0 was dropped
        assertEquals("msg-10", drained.last().messageId)
    }

    @Test
    fun `enqueue continues dropping oldest as capacity is exceeded`() {
        repeat(10) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        // Add 5 more events - should drop msg-0 through msg-4
        repeat(5) { i ->
            queue.enqueue(createMockEvent("msg-${10 + i}"))
        }

        assertEquals(10, queue.size())
        val drained = queue.drain(10)
        assertEquals("msg-5", drained.first().messageId)
        assertEquals("msg-14", drained.last().messageId)
    }

    @Test
    fun `queue with capacity 1 keeps only latest event`() {
        val smallQueue = EventQueue(maxCapacity = 1)

        smallQueue.enqueue(createMockEvent("msg-1"))
        smallQueue.enqueue(createMockEvent("msg-2"))
        smallQueue.enqueue(createMockEvent("msg-3"))

        assertEquals(1, smallQueue.size())
        val peeked = smallQueue.peek()
        assertEquals("msg-3", peeked?.messageId)
    }

    // ===== Requeue Operations =====

    @Test
    fun `requeueAtFront adds events to front of queue`() {
        queue.enqueue(createMockEvent("msg-3"))
        queue.enqueue(createMockEvent("msg-4"))

        val toRequeue = listOf(
            createMockEvent("msg-1"),
            createMockEvent("msg-2")
        )
        queue.requeueAtFront(toRequeue)

        assertEquals(4, queue.size())

        val drained = queue.drain(4)
        assertEquals(listOf("msg-1", "msg-2", "msg-3", "msg-4"), drained.map { it.messageId })
    }

    @Test
    fun `requeueAtFront maintains order of requeued events`() {
        val toRequeue = listOf(
            createMockEvent("msg-1"),
            createMockEvent("msg-2"),
            createMockEvent("msg-3")
        )

        queue.requeueAtFront(toRequeue)

        val drained = queue.drain(3)
        assertEquals(listOf("msg-1", "msg-2", "msg-3"), drained.map { it.messageId })
    }

    @Test
    fun `requeueAtFront with empty list does nothing`() {
        queue.enqueue(createMockEvent("msg-1"))

        queue.requeueAtFront(emptyList())

        assertEquals(1, queue.size())
    }

    @Test
    fun `requeueAtFront respects capacity and drops newest`() {
        // Fill queue to capacity
        repeat(10) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        // Try to requeue 3 events - should drop 3 newest (msg-7, msg-8, msg-9)
        val toRequeue = listOf(
            createMockEvent("requeue-1"),
            createMockEvent("requeue-2"),
            createMockEvent("requeue-3")
        )
        queue.requeueAtFront(toRequeue)

        assertEquals(10, queue.size())
        val drained = queue.drain(10)

        // Should have requeued items first, then msg-0 through msg-6
        assertEquals("requeue-1", drained[0].messageId)
        assertEquals("requeue-2", drained[1].messageId)
        assertEquals("requeue-3", drained[2].messageId)
        assertEquals("msg-0", drained[3].messageId)
        assertEquals("msg-6", drained[9].messageId)
    }

    // ===== Concurrent Operations =====

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
    fun `concurrent drain operations are thread-safe`() {
        // Fill queue
        repeat(100) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        val threadCount = 5
        val latch = CountDownLatch(threadCount)
        val drainedEvents = ConcurrentLinkedQueue<EnrichedEventPayload>()

        val threads = (1..threadCount).map {
            thread {
                val drained = queue.drain(2)
                drainedEvents.addAll(drained)
                latch.countDown()
            }
        }

        latch.await()
        threads.forEach { it.join() }

        // Should have drained exactly 10 events (5 threads × 2 events)
        assertEquals(10, drainedEvents.size)

        // All drained events should be unique (no duplicates)
        val messageIds = drainedEvents.map { it.messageId }
        assertEquals(messageIds.size, messageIds.toSet().size)
    }

    @Test
    fun `concurrent enqueue and drain are thread-safe`() {
        val enqueueCount = 1000
        val drainCount = 10
        val latch = CountDownLatch(2)

        // Enqueue thread
        val enqueueThread = thread {
            repeat(enqueueCount) { i ->
                queue.enqueue(createMockEvent("msg-$i"))
                Thread.sleep(1) // Small delay to interleave operations
            }
            latch.countDown()
        }

        // Drain thread
        val drainThread = thread {
            repeat(drainCount) {
                queue.drain(5)
                Thread.sleep(5)
            }
            latch.countDown()
        }

        latch.await()
        enqueueThread.join()
        drainThread.join()

        // Queue should be in consistent state (max 10 items)
        assertTrue(queue.size() <= 10)
    }

    @Test
    fun `concurrent size checks are thread-safe`() {
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val sizes = ConcurrentLinkedQueue<Int>()

        repeat(5) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        val threads = (1..threadCount).map {
            thread {
                repeat(100) {
                    sizes.add(queue.size())
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
    fun `concurrent peek operations are thread-safe`() {
        queue.enqueue(createMockEvent("msg-1"))

        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val peekedEvents = ConcurrentLinkedQueue<String?>()

        val threads = (1..threadCount).map {
            thread {
                repeat(100) {
                    val peeked = queue.peek()
                    peekedEvents.add(peeked?.messageId)
                }
                latch.countDown()
            }
        }

        latch.await()
        threads.forEach { it.join() }

        // All peek operations should return the same event or null
        val uniqueValues = peekedEvents.toSet()
        assertTrue(uniqueValues.size <= 2) // Either "msg-1" or null
    }

    // ===== Debug Info =====

    @Test
    fun `getDebugInfo returns correct information`() {
        repeat(3) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        val debugInfo = queue.getDebugInfo()

        assertEquals(3, debugInfo["size"])
        assertEquals(10, debugInfo["capacity"])
        assertEquals(false, debugInfo["isEmpty"])
        assertEquals(30.0, debugInfo["utilizationPercent"])
    }

    @Test
    fun `getDebugInfo shows empty queue`() {
        val debugInfo = queue.getDebugInfo()

        assertEquals(0, debugInfo["size"])
        assertEquals(10, debugInfo["capacity"])
        assertEquals(true, debugInfo["isEmpty"])
        assertEquals(0.0, debugInfo["utilizationPercent"])
    }

    @Test
    fun `getDebugInfo shows full queue`() {
        repeat(10) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        val debugInfo = queue.getDebugInfo()

        assertEquals(10, debugInfo["size"])
        assertEquals(10, debugInfo["capacity"])
        assertEquals(false, debugInfo["isEmpty"])
        assertEquals(100.0, debugInfo["utilizationPercent"])
    }

    // ===== Edge Cases =====

    @Test
    fun `queue with large capacity works correctly`() {
        val largeQueue = EventQueue(maxCapacity = 10000)

        repeat(5000) { i ->
            largeQueue.enqueue(createMockEvent("msg-$i"))
        }

        assertEquals(5000, largeQueue.size())

        val drained = largeQueue.drain(2500)
        assertEquals(2500, drained.size)
        assertEquals(2500, largeQueue.size())
    }

    @Test
    fun `multiple drain calls empty queue completely`() {
        repeat(20) { i ->
            queue.enqueue(createMockEvent("msg-$i"))
        }

        // Queue capacity is 10, so only 10 events remain
        assertEquals(10, queue.size())

        queue.drain(5)
        assertEquals(5, queue.size())

        queue.drain(3)
        assertEquals(2, queue.size())

        queue.drain(10)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `enqueue after drain maintains FIFO order`() {
        queue.enqueue(createMockEvent("msg-1"))
        queue.enqueue(createMockEvent("msg-2"))

        queue.drain(1) // Drain msg-1

        queue.enqueue(createMockEvent("msg-3"))
        queue.enqueue(createMockEvent("msg-4"))

        val drained = queue.drain(10)

        assertEquals(listOf("msg-2", "msg-3", "msg-4"), drained.map { it.messageId })
    }

    // ===== Helper Methods =====

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

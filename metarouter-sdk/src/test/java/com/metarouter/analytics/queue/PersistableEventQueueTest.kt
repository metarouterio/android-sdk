package com.metarouter.analytics.queue

import com.metarouter.analytics.storage.EventDiskStore
import com.metarouter.analytics.storage.QueueSnapshot
import com.metarouter.analytics.types.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PersistableEventQueueTest {

    private lateinit var tempDir: File
    private lateinit var diskStore: EventDiskStore
    private lateinit var queue: PersistableEventQueue

    @Before
    fun setup() {
        tempDir = createTempDir("metarouter-pq-test")
        diskStore = EventDiskStore(tempDir)
        PersistableEventQueue.resetRehydrationFlag()
        queue = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024
        )
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
        PersistableEventQueue.resetRehydrationFlag()
    }

    // ===== Enqueue (memory-only) =====

    @Test
    fun `enqueue writes to memory only - no disk file created`() {
        queue.enqueue(createTestEvent("msg-1"))

        assertEquals(1, queue.size())
        assertNull(diskStore.read())
    }

    @Test
    fun `enqueue tracks estimated byte size`() {
        queue.enqueue(createTestEvent("msg-1"))
        assertTrue(queue.estimatedSizeBytes() > 0)
    }

    @Test
    fun `enqueue drops oldest when event count capacity exceeded`() {
        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024
        )

        repeat(6) { i ->
            smallQueue.enqueue(createTestEvent("msg-$i"))
        }

        assertEquals(5, smallQueue.size())
        val drained = smallQueue.drain(5)
        assertEquals("msg-1", drained[0].messageId)
    }

    @Test
    fun `enqueue drops oldest when byte capacity exceeded`() {
        val tinyQueue = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 500 // 500 bytes max capacity
        )

        repeat(10) { i ->
            tinyQueue.enqueue(createTestEvent("msg-$i"))
        }

        assertTrue(tinyQueue.size() < 10)
        assertTrue(tinyQueue.estimatedSizeBytes() <= 500)
    }

    // ===== Drain (memory-only) =====

    @Test
    fun `drain reads from memory only`() {
        repeat(3) { i ->
            queue.enqueue(createTestEvent("msg-$i"))
        }

        val drained = queue.drain(2)

        assertEquals(2, drained.size)
        assertEquals(1, queue.size())
    }

    @Test
    fun `drain updates estimated byte size`() {
        repeat(5) { i ->
            queue.enqueue(createTestEvent("msg-$i"))
        }
        val sizeBefore = queue.estimatedSizeBytes()

        queue.drain(3)

        assertTrue(queue.estimatedSizeBytes() < sizeBefore)
    }

    // ===== Flush to Disk =====

    @Test
    fun `flushToDisk writes current memory state to disk`() {
        repeat(3) { i ->
            queue.enqueue(createTestEvent("msg-$i"))
        }

        queue.flushToDisk()

        val snapshot = diskStore.read()
        assertNotNull(snapshot)
        assertEquals(3, snapshot!!.events.size)
        assertEquals("msg-0", snapshot.events[0].messageId)
    }

    @Test
    fun `flushToDisk fully overwrites previous snapshot`() {
        queue.enqueue(createTestEvent("msg-1"))
        queue.flushToDisk()

        queue.drain(1)
        queue.enqueue(createTestEvent("msg-2"))
        queue.enqueue(createTestEvent("msg-3"))
        queue.flushToDisk()

        val snapshot = diskStore.read()
        assertNotNull(snapshot)
        assertEquals(2, snapshot!!.events.size)
        assertEquals("msg-2", snapshot.events[0].messageId)
        assertEquals("msg-3", snapshot.events[1].messageId)
    }

    @Test
    fun `flushToDisk with empty queue writes empty snapshot`() {
        queue.flushToDisk()

        val snapshot = diskStore.read()
        assertNotNull(snapshot)
        assertEquals(0, snapshot!!.events.size)
    }

    // ===== Flush Threshold =====

    @Test
    fun `shouldFlushToDisk returns true when event threshold reached`() {
        val lowThreshold = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 3,
            flushThresholdBytes = 50 * 1024 * 1024,
            maxCapacityBytes = 100 * 1024 * 1024
        )

        repeat(2) { lowThreshold.enqueue(createTestEvent("msg-$it")) }
        assertFalse(lowThreshold.shouldFlushToDisk())

        lowThreshold.enqueue(createTestEvent("msg-2"))
        assertTrue(lowThreshold.shouldFlushToDisk())
    }

    @Test
    fun `shouldFlushToDisk returns true when byte threshold reached`() {
        val lowByteThreshold = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 50000,
            flushThresholdBytes = 500,
            maxCapacityBytes = 100 * 1024 * 1024
        )

        repeat(5) { i ->
            lowByteThreshold.enqueue(createTestEvent("msg-$i"))
        }

        assertTrue(lowByteThreshold.shouldFlushToDisk())
    }

    // ===== Rehydration =====

    @Test
    fun `rehydrate loads events from disk into memory`() {
        val events = listOf(createTestEvent("disk-1"), createTestEvent("disk-2"))
        val snapshot = QueueSnapshot(version = 1, events = events)
        diskStore.write(snapshot)

        queue.rehydrate()

        assertEquals(2, queue.size())
        val drained = queue.drain(10)
        assertEquals("disk-1", drained[0].messageId)
        assertEquals("disk-2", drained[1].messageId)
    }

    @Test
    fun `rehydrate only happens once per process`() {
        val events = listOf(createTestEvent("disk-1"))
        diskStore.write(QueueSnapshot(version = 1, events = events))

        queue.rehydrate()
        assertEquals(1, queue.size())

        queue.drain(1)
        val queue2 = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024
        )

        diskStore.write(QueueSnapshot(version = 1, events = listOf(createTestEvent("disk-2"))))

        queue2.rehydrate()
        assertEquals(0, queue2.size())
    }

    @Test
    fun `rehydrate with no disk file does nothing`() {
        queue.rehydrate()
        assertEquals(0, queue.size())
    }

    @Test
    fun `rehydrate drops oldest events if disk exceeds capacity`() {
        val smallCapQueue = PersistableEventQueue(
            maxCapacity = 3,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024
        )

        val events = (1..5).map { createTestEvent("msg-$it") }
        diskStore.write(QueueSnapshot(version = 1, events = events))

        smallCapQueue.rehydrate()

        assertEquals(3, smallCapQueue.size())
        val drained = smallCapQueue.drain(10)
        assertEquals("msg-3", drained[0].messageId)
        assertEquals("msg-4", drained[1].messageId)
        assertEquals("msg-5", drained[2].messageId)
    }

    @Test
    fun `rehydrate deletes disk file after loading`() {
        val events = listOf(createTestEvent("disk-1"))
        diskStore.write(QueueSnapshot(version = 1, events = events))

        queue.rehydrate()

        assertNull(diskStore.read())
    }

    // ===== Clear =====

    @Test
    fun `clear removes memory and deletes disk snapshot`() {
        queue.enqueue(createTestEvent("msg-1"))
        queue.flushToDisk()

        queue.clear()

        assertEquals(0, queue.size())
        assertNull(diskStore.read())
    }

    @Test
    fun `clear resets byte tracking`() {
        queue.enqueue(createTestEvent("msg-1"))
        assertTrue(queue.estimatedSizeBytes() > 0)

        queue.clear()

        assertEquals(0L, queue.estimatedSizeBytes())
    }

    // ===== Thread Safety =====

    @Test
    fun `concurrent enqueue and flush are safe`() {
        val latch = java.util.concurrent.CountDownLatch(2)

        val enqueueThread = Thread {
            repeat(100) { i ->
                queue.enqueue(createTestEvent("msg-$i"))
            }
            latch.countDown()
        }

        val flushThread = Thread {
            repeat(10) {
                queue.flushToDisk()
            }
            latch.countDown()
        }

        enqueueThread.start()
        flushThread.start()
        latch.await()

        assertTrue(queue.size() in 0..100)
    }

    // ===== requeueToFront =====

    @Test
    fun `requeueToFront updates byte tracking`() {
        repeat(3) { i ->
            queue.enqueue(createTestEvent("msg-$i"))
        }
        val sizeBefore = queue.estimatedSizeBytes()

        val drained = queue.drain(2)
        val sizeAfterDrain = queue.estimatedSizeBytes()
        assertTrue(sizeAfterDrain < sizeBefore)

        queue.requeueToFront(drained)
        assertEquals(3, queue.size())
        assertTrue(queue.estimatedSizeBytes() > sizeAfterDrain)
    }

    private fun createTestEvent(messageId: String): EnrichedEventPayload {
        return EnrichedEventPayload(
            type = EventType.TRACK,
            event = "Test Event",
            userId = "test-user",
            anonymousId = "test-anon",
            groupId = null,
            traits = null,
            properties = null,
            timestamp = "2026-01-01T00:00:00.000Z",
            context = EventContext(
                library = LibraryContext(name = "metarouter-android", version = "1.0.0")
            ),
            messageId = messageId,
            writeKey = "test-key",
            sentAt = null
        )
    }
}

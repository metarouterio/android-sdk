package com.metarouter.analytics.queue

import android.util.Log
import com.metarouter.analytics.InitOptions
import com.metarouter.analytics.dispatcher.Dispatcher
import com.metarouter.analytics.dispatcher.DispatcherConfig
import com.metarouter.analytics.network.CircuitBreaker
import com.metarouter.analytics.network.FakeNetworkClient
import com.metarouter.analytics.network.NetworkResponse
import com.metarouter.analytics.storage.EventDiskStore
import com.metarouter.analytics.storage.QueueSnapshot
import com.metarouter.analytics.types.*
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class PersistableEventQueueTest {

    private lateinit var tempDir: File
    private lateinit var diskStore: EventDiskStore
    private lateinit var queue: PersistableEventQueue

    @Before
    fun setup() {
        tempDir = createTempDirectory("metarouter-pq-test").toFile()
        diskStore = EventDiskStore(tempDir)
        PersistableEventQueue.resetRehydrationFlag()
        queue = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024,
            eventTTLMs = 0 // Disabled for general tests; TTL-specific tests create their own queue
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
    fun `flushToDisk with empty queue skips write and deletes existing snapshot`() {
        // Write a snapshot with events first
        queue.enqueue(createTestEvent("msg-1"))
        queue.flushToDisk()
        assertNotNull(diskStore.read()) // snapshot exists

        // Drain all events, then flush empty queue
        queue.drain(1)
        queue.flushToDisk()

        // Existing snapshot should be deleted, not overwritten with empty
        assertNull(diskStore.read())
    }

    @Test
    fun `flushToDisk with empty queue and no existing snapshot is a no-op`() {
        // No events, no prior snapshot
        queue.flushToDisk()
        assertNull(diskStore.read())
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
            maxCapacityBytes = 5 * 1024 * 1024,
            eventTTLMs = 0
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

    // ===== Event TTL =====

    @Test
    fun `rehydrate drops events older than TTL`() {
        val ttlQueue = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024,
            eventTTLMs = 7L * 24 * 60 * 60 * 1000 // 7 days
        )

        val now = System.currentTimeMillis()
        val eightDaysAgo = formatTimestamp(now - 8L * 24 * 60 * 60 * 1000)
        val twoDaysAgo = formatTimestamp(now - 2L * 24 * 60 * 60 * 1000)
        val recent = formatTimestamp(now - 60_000) // 1 minute ago

        val events = listOf(
            createTestEvent("old-1", timestamp = eightDaysAgo),
            createTestEvent("mid-1", timestamp = twoDaysAgo),
            createTestEvent("new-1", timestamp = recent)
        )
        diskStore.write(QueueSnapshot(version = 1, events = events))

        ttlQueue.rehydrate()

        assertEquals(2, ttlQueue.size())
        val drained = ttlQueue.drain(10)
        assertEquals("mid-1", drained[0].messageId)
        assertEquals("new-1", drained[1].messageId)
    }

    @Test
    fun `rehydrate TTL filter runs before capacity trim`() {
        val ttlQueue = PersistableEventQueue(
            maxCapacity = 2,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024,
            eventTTLMs = 7L * 24 * 60 * 60 * 1000
        )

        val now = System.currentTimeMillis()
        val eightDaysAgo = formatTimestamp(now - 8L * 24 * 60 * 60 * 1000)
        val twoDaysAgo = formatTimestamp(now - 2L * 24 * 60 * 60 * 1000)
        val oneDayAgo = formatTimestamp(now - 1L * 24 * 60 * 60 * 1000)
        val recent = formatTimestamp(now - 60_000)

        // 4 events: 2 expired, 2 valid. maxCapacity = 2.
        // TTL filter removes the 2 expired. 2 valid remain. Capacity is satisfied.
        val events = listOf(
            createTestEvent("expired-1", timestamp = eightDaysAgo),
            createTestEvent("expired-2", timestamp = eightDaysAgo),
            createTestEvent("valid-1", timestamp = twoDaysAgo),
            createTestEvent("valid-2", timestamp = oneDayAgo)
        )
        diskStore.write(QueueSnapshot(version = 1, events = events))

        ttlQueue.rehydrate()

        assertEquals(2, ttlQueue.size())
        val drained = ttlQueue.drain(10)
        assertEquals("valid-1", drained[0].messageId)
        assertEquals("valid-2", drained[1].messageId)
    }

    @Test
    fun `rehydrate with unparseable timestamp keeps event`() {
        val ttlQueue = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024,
            eventTTLMs = 7L * 24 * 60 * 60 * 1000
        )

        val events = listOf(
            createTestEvent("bad-ts", timestamp = "not-a-timestamp"),
            createTestEvent("good-ts", timestamp = formatTimestamp(System.currentTimeMillis() - 60_000))
        )
        diskStore.write(QueueSnapshot(version = 1, events = events))

        ttlQueue.rehydrate()

        // Unparseable timestamp should be kept (fail-open), not dropped
        assertEquals(2, ttlQueue.size())
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

    // ===== End-to-end Scenarios =====

    @Test
    fun `full lifecycle - enqueue, background flush, process restart, rehydrate`() {
        // Simulate first session
        repeat(10) { i ->
            queue.enqueue(createTestEvent("session1-msg-$i"))
        }
        assertEquals(10, queue.size())

        // App backgrounds -> flush to disk
        queue.flushToDisk()

        // Simulate process restart: create new queue with same disk store
        PersistableEventQueue.resetRehydrationFlag()
        val queue2 = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024,
            eventTTLMs = 0
        )

        queue2.rehydrate()

        assertEquals(10, queue2.size())
        val drained = queue2.drain(10)
        assertEquals("session1-msg-0", drained[0].messageId)
        assertEquals("session1-msg-9", drained[9].messageId)
    }

    @Test
    fun `events drained before background are not in snapshot`() {
        repeat(5) { i ->
            queue.enqueue(createTestEvent("msg-$i"))
        }

        // Dispatcher drains some events
        queue.drain(3)

        // Background flush
        queue.flushToDisk()

        val snapshot = diskStore.read()
        assertNotNull(snapshot)
        assertEquals(2, snapshot!!.events.size)
        assertEquals("msg-3", snapshot.events[0].messageId)
        assertEquals("msg-4", snapshot.events[1].messageId)
    }

    @Test
    fun `normal session with no backgrounding never touches disk`() {
        repeat(10) { i ->
            queue.enqueue(createTestEvent("msg-$i"))
        }
        queue.drain(10)

        assertNull(diskStore.read())
        assertFalse(queue.shouldFlushToDisk())
    }

    @Test
    fun `multiple flush-rehydrate cycles preserve data correctly`() {
        // Cycle 1: enqueue and flush
        queue.enqueue(createTestEvent("cycle1-a"))
        queue.enqueue(createTestEvent("cycle1-b"))
        queue.flushToDisk()

        // Cycle 2: restart, rehydrate, add more, flush again
        PersistableEventQueue.resetRehydrationFlag()
        val queue2 = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024,
            eventTTLMs = 0
        )
        queue2.rehydrate()
        queue2.enqueue(createTestEvent("cycle2-c"))
        queue2.flushToDisk()

        // Cycle 3: restart, rehydrate
        PersistableEventQueue.resetRehydrationFlag()
        val queue3 = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024,
            eventTTLMs = 0
        )
        queue3.rehydrate()

        assertEquals(3, queue3.size())
        val drained = queue3.drain(10)
        assertEquals("cycle1-a", drained[0].messageId)
        assertEquals("cycle1-b", drained[1].messageId)
        assertEquals("cycle2-c", drained[2].messageId)
    }

    @Test
    fun `flush overwrites previous state - no duplicates`() {
        queue.enqueue(createTestEvent("batch1-a"))
        queue.enqueue(createTestEvent("batch1-b"))
        queue.flushToDisk()

        // Drain all, add new events
        queue.drain(10)
        queue.enqueue(createTestEvent("batch2-a"))
        queue.flushToDisk()

        PersistableEventQueue.resetRehydrationFlag()
        val queue2 = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            flushThresholdEvents = 500,
            flushThresholdBytes = 2 * 1024 * 1024,
            maxCapacityBytes = 5 * 1024 * 1024,
            eventTTLMs = 0
        )
        queue2.rehydrate()

        // Should only have batch2 events — full overwrite, no duplicates
        assertEquals(1, queue2.size())
        val drained = queue2.drain(10)
        assertEquals("batch2-a", drained[0].messageId)
    }

    // ===== Offline Overflow =====

    @Test
    fun `memory queue overflow while offline buffers then flushes to disk`() {
        val overflowDir = createTempDirectory("metarouter-overflow-test").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            overflowBufferBatchThreshold = 3, // Low threshold for testing
            eventTTLMs = 0
        )

        smallQueue.setOfflineOverflowEnabled(true)

        // Fill queue to capacity
        repeat(5) { i -> smallQueue.enqueue(createTestEvent("fill-$i")) }
        assertEquals(5, smallQueue.size())

        // Enqueue more — should overflow to buffer, then to disk at threshold=3
        repeat(4) { i -> smallQueue.enqueue(createTestEvent("overflow-$i")) }

        // Memory queue should still be at max capacity
        assertEquals(5, smallQueue.size())

        // Flush remaining buffer to disk
        smallQueue.flushOverflowBufferToDisk()

        // Verify overflow events are on disk
        // Queue was: fill-0..fill-4. When overflow-0 comes in, fill-0 is evicted to buffer.
        // overflow-1 → fill-1 evicted. overflow-2 → fill-2 evicted (buffer hits threshold=3, auto-flush).
        // overflow-3 → fill-3 evicted. Explicit flush writes remaining buffer.
        val snapshot = overflowDiskStore.read()
        assertNotNull(snapshot)
        assertEquals(4, snapshot!!.events.size)
        assertEquals("fill-0", snapshot.events[0].messageId)
        assertEquals("fill-1", snapshot.events[1].messageId)
        assertEquals("fill-2", snapshot.events[2].messageId)
        assertEquals("fill-3", snapshot.events[3].messageId)

        overflowDir.deleteRecursively()
    }

    @Test
    fun `overflow writes are batched not per-event`() {
        val overflowDir = createTempDirectory("metarouter-overflow-batch").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            overflowBufferBatchThreshold = 10, // Won't trigger during this test
            eventTTLMs = 0
        )

        smallQueue.setOfflineOverflowEnabled(true)

        // Fill queue
        repeat(5) { i -> smallQueue.enqueue(createTestEvent("fill-$i")) }

        // Overflow 8 events — batch threshold is 10, so no auto-flush to disk
        repeat(8) { i -> smallQueue.enqueue(createTestEvent("overflow-$i")) }

        // No disk write should have happened yet (buffer < threshold)
        assertNull(overflowDiskStore.read())

        // Explicit flush writes all at once
        smallQueue.flushOverflowBufferToDisk()
        val snapshot = overflowDiskStore.read()
        assertNotNull(snapshot)
        assertEquals(8, snapshot!!.events.size)

        overflowDir.deleteRecursively()
    }

    @Test
    fun `offline disk overflow respects maxOfflineDiskEvents cap`() {
        val overflowDir = createTempDirectory("metarouter-overflow-cap").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 10, // Cap at 10
            overflowDiskStore = overflowDiskStore,
            overflowBufferBatchThreshold = 5,
            eventTTLMs = 0
        )

        smallQueue.setOfflineOverflowEnabled(true)

        // Fill queue
        repeat(5) { i -> smallQueue.enqueue(createTestEvent("fill-$i")) }

        // Overflow 15 events (exceeds maxOfflineDiskEvents=10)
        repeat(15) { i -> smallQueue.enqueue(createTestEvent("overflow-$i")) }
        smallQueue.flushOverflowBufferToDisk()

        val snapshot = overflowDiskStore.read()
        assertNotNull(snapshot)
        assertTrue("Should cap at maxOfflineDiskEvents", snapshot!!.events.size <= 10)

        overflowDir.deleteRecursively()
    }

    @Test
    fun `drainDiskOverflowToNetwork sends directly to network`() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val overflowDir = createTempDirectory("metarouter-overflow-drain").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val overflowQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            overflowBufferBatchThreshold = 100,
            eventTTLMs = 0
        )

        // Pre-populate overflow disk
        val events = (1..5).map { createTestEvent("overflow-$it") }
        overflowDiskStore.write(QueueSnapshot(version = 1, events = events))

        // Set up dispatcher with fake network
        val fakeClient = FakeNetworkClient()
        fakeClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        val initOptions = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://api.example.com",
            flushIntervalSeconds = 10
        )
        val dispatcher = Dispatcher(
            options = initOptions,
            queue = overflowQueue,
            networkClient = fakeClient,
            circuitBreaker = CircuitBreaker(),
            scope = this,
            config = DispatcherConfig()
        )

        val drained = overflowQueue.drainDiskOverflowToNetwork(dispatcher)

        assertEquals(5, drained)
        assertEquals(1, fakeClient.requests.size) // Batch of 5 events
        assertNull(overflowDiskStore.read()) // Disk file deleted

        // Memory queue should be untouched (0 events — drain goes directly to network)
        assertEquals(0, overflowQueue.size())

        overflowDir.deleteRecursively()
        unmockkStatic(Log::class)
    }

    @Test
    fun `drainDiskOverflowToNetwork stops on network failure`() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val overflowDir = createTempDirectory("metarouter-overflow-fail").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val overflowQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            overflowBufferBatchThreshold = 100,
            eventTTLMs = 0
        )

        // Pre-populate overflow disk
        val events = (1..5).map { createTestEvent("overflow-$it") }
        overflowDiskStore.write(QueueSnapshot(version = 1, events = events))

        // Set up dispatcher that fails
        val fakeClient = FakeNetworkClient()
        fakeClient.nextResponse = NetworkResponse(500, emptyMap(), null)
        val initOptions = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://api.example.com",
            flushIntervalSeconds = 10
        )
        val dispatcher = Dispatcher(
            options = initOptions,
            queue = overflowQueue,
            networkClient = fakeClient,
            circuitBreaker = CircuitBreaker(),
            scope = this,
            config = DispatcherConfig()
        )

        val drained = overflowQueue.drainDiskOverflowToNetwork(dispatcher)

        assertEquals(0, drained)
        // Events should still be on disk
        val remaining = overflowDiskStore.read()
        assertNotNull(remaining)
        assertEquals(5, remaining!!.events.size)

        overflowDir.deleteRecursively()
        unmockkStatic(Log::class)
    }

    @Test
    fun `clear deletes overflow disk file`() {
        val overflowDir = createTempDirectory("metarouter-overflow-clear").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val overflowQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        // Write overflow data
        overflowDiskStore.write(QueueSnapshot(version = 1, events = listOf(createTestEvent("overflow-1"))))
        overflowQueue.enqueue(createTestEvent("memory-1"))

        overflowQueue.clear()

        assertEquals(0, overflowQueue.size())
        assertNull(diskStore.read())
        assertNull(overflowDiskStore.read())

        overflowDir.deleteRecursively()
    }

    @Test
    fun `app killed while offline, relaunch online, disk overflow drains to network`() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val overflowDir = createTempDirectory("metarouter-overflow-relaunch").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        // Simulate previous session: overflow events were written to disk
        val previousEvents = (1..3).map { createTestEvent("prev-session-$it") }
        overflowDiskStore.write(QueueSnapshot(version = 1, events = previousEvents))

        // New session — create fresh queue (simulating app relaunch)
        PersistableEventQueue.resetRehydrationFlag()
        val freshQueue = PersistableEventQueue(
            maxCapacity = 2000,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )
        freshQueue.rehydrate()

        // Set up dispatcher with working network (online at launch)
        val fakeClient = FakeNetworkClient()
        fakeClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        val initOptions = InitOptions(
            writeKey = "test-key",
            ingestionHost = "https://api.example.com",
            flushIntervalSeconds = 10
        )
        val dispatcher = Dispatcher(
            options = initOptions,
            queue = freshQueue,
            networkClient = fakeClient,
            circuitBreaker = CircuitBreaker(),
            scope = this,
            config = DispatcherConfig()
        )

        // Drain overflow from previous session
        val drained = freshQueue.drainDiskOverflowToNetwork(dispatcher)

        assertEquals(3, drained)
        assertEquals(1, fakeClient.requests.size) // Direct to network
        assertNull(overflowDiskStore.read()) // Cleaned up

        overflowDir.deleteRecursively()
        unmockkStatic(Log::class)
    }

    @Test
    fun `offline overflow disabled does not write to disk on overflow`() {
        val overflowDir = createTempDirectory("metarouter-overflow-disabled").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            overflowBufferBatchThreshold = 3,
            eventTTLMs = 0
        )

        // Overflow NOT enabled (default)
        repeat(5) { i -> smallQueue.enqueue(createTestEvent("fill-$i")) }
        repeat(3) { i -> smallQueue.enqueue(createTestEvent("overflow-$i")) }

        smallQueue.flushOverflowBufferToDisk()

        // No overflow disk data — events were dropped normally
        assertNull(overflowDiskStore.read())
        assertEquals(5, smallQueue.size())

        overflowDir.deleteRecursively()
    }

    @Test
    fun `setOfflineOverflowEnabled toggles overflow behavior`() {
        val overflowDir = createTempDirectory("metarouter-overflow-toggle").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            overflowBufferBatchThreshold = 100,
            eventTTLMs = 0
        )

        // Fill queue
        repeat(5) { i -> smallQueue.enqueue(createTestEvent("fill-$i")) }

        // Overflow disabled — events dropped
        smallQueue.enqueue(createTestEvent("dropped-1"))
        assertEquals(5, smallQueue.size())
        assertEquals(0, smallQueue.offlineOverflowCount())

        // Enable overflow
        smallQueue.setOfflineOverflowEnabled(true)
        smallQueue.enqueue(createTestEvent("buffered-1"))
        assertEquals(5, smallQueue.size())
        assertTrue(smallQueue.offlineOverflowCount() > 0)

        // Disable overflow again
        smallQueue.setOfflineOverflowEnabled(false)
        smallQueue.enqueue(createTestEvent("dropped-2"))
        assertEquals(5, smallQueue.size())

        overflowDir.deleteRecursively()
    }

    private fun createTestEvent(messageId: String, timestamp: String = "2026-01-01T00:00:00.000Z"): EnrichedEventPayload {
        return EnrichedEventPayload(
            type = EventType.TRACK,
            event = "Test Event",
            userId = "test-user",
            anonymousId = "test-anon",
            groupId = null,
            traits = null,
            properties = null,
            timestamp = timestamp,
            context = EventContext(
                library = LibraryContext(name = "metarouter-android", version = "1.0.0")
            ),
            messageId = messageId,
            writeKey = "test-key",
            sentAt = null
        )
    }

    private fun formatTimestamp(epochMs: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return format.format(java.util.Date(epochMs))
    }
}

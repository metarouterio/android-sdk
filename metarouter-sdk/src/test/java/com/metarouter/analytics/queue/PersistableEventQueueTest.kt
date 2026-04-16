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
import kotlinx.coroutines.async
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
    fun `capacity overflow flushes entire queue to disk`() {
        val overflowDir = createTempDirectory("metarouter-overflow-test").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        // Fill queue to capacity
        repeat(5) { i -> smallQueue.enqueue(createTestEvent("fill-$i")) }
        assertEquals(5, smallQueue.size())

        // Enqueue one more — should flush all 5 to disk, then add the new event
        smallQueue.enqueue(createTestEvent("overflow-0"))

        // Memory queue should have just the new event
        assertEquals(1, smallQueue.size())

        // All 5 original events should be on overflow disk
        val snapshot = overflowDiskStore.read()
        assertNotNull(snapshot)
        assertEquals(5, snapshot!!.events.size)
        assertEquals("fill-0", snapshot.events[0].messageId)
        assertEquals("fill-4", snapshot.events[4].messageId)

        // Drain memory to verify contents
        val drained = smallQueue.drain(10)
        assertEquals(1, drained.size)
        assertEquals("overflow-0", drained[0].messageId)

        overflowDir.deleteRecursively()
    }

    @Test
    fun `flushToOfflineStorage drains entire queue to disk`() {
        val overflowDir = createTempDirectory("metarouter-overflow-flush").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        // Fill queue with 3 events
        repeat(3) { i -> smallQueue.enqueue(createTestEvent("event-$i")) }
        assertEquals(3, smallQueue.size())

        // Flush to offline storage (simulates dispatcher offline flush)
        val flushed = smallQueue.flushToOfflineStorage()
        assertTrue(flushed)

        // Memory queue should be empty
        assertEquals(0, smallQueue.size())

        // Events should be on overflow disk
        val snapshot = overflowDiskStore.read()
        assertNotNull(snapshot)
        assertEquals(3, snapshot!!.events.size)

        overflowDir.deleteRecursively()
    }

    @Test
    fun `flushToOfflineStorage appends to existing overflow disk data`() {
        val overflowDir = createTempDirectory("metarouter-overflow-append").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        // Pre-populate overflow disk with 3 events
        val existing = (1..3).map { createTestEvent("existing-$it") }
        overflowDiskStore.write(QueueSnapshot(version = 1, events = existing))

        // Add 2 events to memory and flush
        repeat(2) { i -> smallQueue.enqueue(createTestEvent("new-$i")) }
        smallQueue.flushToOfflineStorage()

        // Disk should have all 5
        val snapshot = overflowDiskStore.read()
        assertNotNull(snapshot)
        assertEquals(5, snapshot!!.events.size)
        assertEquals("existing-1", snapshot.events[0].messageId)
        assertEquals("new-1", snapshot.events[4].messageId)

        overflowDir.deleteRecursively()
    }

    @Test
    fun `offline disk overflow respects maxOfflineDiskEvents cap`() {
        val overflowDir = createTempDirectory("metarouter-overflow-cap").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 8,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        // Fill and overflow multiple times to exceed cap
        // Round 1: fill 5, overflow triggers flush of 5 to disk
        repeat(5) { i -> smallQueue.enqueue(createTestEvent("batch1-$i")) }
        smallQueue.flushToOfflineStorage()

        // Round 2: fill 5 more, flush again — total would be 10, cap is 8
        repeat(5) { i -> smallQueue.enqueue(createTestEvent("batch2-$i")) }
        smallQueue.flushToOfflineStorage()

        val snapshot = overflowDiskStore.read()
        assertNotNull(snapshot)
        assertTrue("Should cap at maxOfflineDiskEvents", snapshot!!.events.size <= 8)

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
        assertEquals(1, fakeClient.requests.size)
        assertNull(overflowDiskStore.read())
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

        val drained = freshQueue.drainDiskOverflowToNetwork(dispatcher)

        assertEquals(3, drained)
        assertEquals(1, fakeClient.requests.size)
        assertNull(overflowDiskStore.read())

        overflowDir.deleteRecursively()
        unmockkStatic(Log::class)
    }

    @Test
    fun `no overflow disk store drops events normally at capacity`() {
        // No overflowDiskStore — events are dropped when queue is full
        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            overflowDiskStore = null,
            eventTTLMs = 0
        )

        repeat(5) { i -> smallQueue.enqueue(createTestEvent("fill-$i")) }
        repeat(3) { i -> smallQueue.enqueue(createTestEvent("overflow-$i")) }

        // Events were dropped, queue stays at capacity
        assertEquals(5, smallQueue.size())
    }

    @Test
    fun `capacity overflow always flushes to disk when overflow store exists`() {
        val overflowDir = createTempDirectory("metarouter-overflow-always").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        // Fill queue
        repeat(5) { i -> smallQueue.enqueue(createTestEvent("fill-$i")) }

        // Next enqueue at capacity flushes entire queue to disk
        smallQueue.enqueue(createTestEvent("triggers-flush"))
        assertEquals(1, smallQueue.size())
        assertNotNull(overflowDiskStore.read())
        assertEquals(5, overflowDiskStore.read()!!.events.size)

        overflowDir.deleteRecursively()
    }

    @Test
    fun `flushToOfflineStorage drains queue when overflow store exists`() {
        val overflowDir = createTempDirectory("metarouter-overflow-toggle").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        repeat(3) { i -> smallQueue.enqueue(createTestEvent("event-$i")) }

        assertTrue(smallQueue.flushToOfflineStorage())
        assertEquals(0, smallQueue.size())
        assertNotNull(overflowDiskStore.read())
        assertEquals(3, overflowDiskStore.read()!!.events.size)

        overflowDir.deleteRecursively()
    }

    @Test
    fun `flushToOfflineStorage returns false without overflow store`() {
        // Queue without overflow store
        val noOverflowQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            overflowDiskStore = null,
            eventTTLMs = 0
        )

        repeat(3) { i -> noOverflowQueue.enqueue(createTestEvent("event-$i")) }

        assertFalse(noOverflowQueue.flushToOfflineStorage())
        assertEquals(3, noOverflowQueue.size())
    }

    // ===== Drain Response Handling =====

    @Test
    fun `drain halves batch on 413 and retries`() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val overflowDir = createTempDirectory("metarouter-overflow-413").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val overflowQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        // Pre-populate overflow disk with 4 events
        val events = (1..4).map { createTestEvent("overflow-$it") }
        overflowDiskStore.write(QueueSnapshot(version = 1, events = events))

        val fakeClient = FakeNetworkClient()
        // First call: 413, second call (halved batch): 200, third call: 200
        fakeClient.enqueueResponses(
            NetworkResponse(413, emptyMap(), null),
            NetworkResponse(200, emptyMap(), null),
            NetworkResponse(200, emptyMap(), null)
        )
        val dispatcher = Dispatcher(
            options = InitOptions(writeKey = "test-key", ingestionHost = "https://api.example.com", flushIntervalSeconds = 10),
            queue = overflowQueue,
            networkClient = fakeClient,
            circuitBreaker = CircuitBreaker(),
            scope = this,
            config = DispatcherConfig()
        )

        val drained = overflowQueue.drainDiskOverflowToNetwork(dispatcher)

        assertEquals(4, drained)
        assertNull(overflowDiskStore.read())

        overflowDir.deleteRecursively()
        unmockkStatic(Log::class)
    }

    @Test
    fun `drain drops oversized event at batchSize 1 on 413`() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val overflowDir = createTempDirectory("metarouter-overflow-413-drop").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val overflowQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        // 2 events — first will be too large, second should still send
        val events = listOf(createTestEvent("big-event"), createTestEvent("normal-event"))
        overflowDiskStore.write(QueueSnapshot(version = 1, events = events))

        val fakeClient = FakeNetworkClient()
        // 413 responses until batch is 1, then 413 again (drop), then 200 for remaining
        fakeClient.enqueueResponses(
            NetworkResponse(413, emptyMap(), null),  // batch=100 -> halve
            NetworkResponse(413, emptyMap(), null),  // batch=50 -> halve (and so on until 1)
            NetworkResponse(413, emptyMap(), null),
            NetworkResponse(413, emptyMap(), null),
            NetworkResponse(413, emptyMap(), null),
            NetworkResponse(413, emptyMap(), null),
            NetworkResponse(413, emptyMap(), null),  // batch=1 -> drop big-event
            NetworkResponse(200, emptyMap(), null)   // normal-event succeeds
        )
        val dispatcher = Dispatcher(
            options = InitOptions(writeKey = "test-key", ingestionHost = "https://api.example.com", flushIntervalSeconds = 10),
            queue = overflowQueue,
            networkClient = fakeClient,
            circuitBreaker = CircuitBreaker(),
            scope = this,
            config = DispatcherConfig()
        )

        val drained = overflowQueue.drainDiskOverflowToNetwork(dispatcher)

        // Only normal-event was successfully sent
        assertEquals(1, drained)
        assertNull(overflowDiskStore.read())

        overflowDir.deleteRecursively()
        unmockkStatic(Log::class)
    }

    @Test
    fun `drain deletes overflow store on fatal config error`() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val overflowDir = createTempDirectory("metarouter-overflow-fatal").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val overflowQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        val events = (1..5).map { createTestEvent("overflow-$it") }
        overflowDiskStore.write(QueueSnapshot(version = 1, events = events))

        val fakeClient = FakeNetworkClient()
        fakeClient.nextResponse = NetworkResponse(401, emptyMap(), null)
        val dispatcher = Dispatcher(
            options = InitOptions(writeKey = "test-key", ingestionHost = "https://api.example.com", flushIntervalSeconds = 10),
            queue = overflowQueue,
            networkClient = fakeClient,
            circuitBreaker = CircuitBreaker(),
            scope = this,
            config = DispatcherConfig()
        )

        val drained = overflowQueue.drainDiskOverflowToNetwork(dispatcher)

        assertEquals(0, drained)
        assertNull(overflowDiskStore.read()) // Overflow store deleted on fatal error

        overflowDir.deleteRecursively()
        unmockkStatic(Log::class)
    }

    @Test
    fun `drain drops batch on client error and continues`() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val overflowDir = createTempDirectory("metarouter-overflow-400").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val overflowQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        // Write 150 events so drain takes 2 batches (100 + 50)
        val events = (1..150).map { createTestEvent("overflow-$it") }
        overflowDiskStore.write(QueueSnapshot(version = 1, events = events))

        val fakeClient = FakeNetworkClient()
        // First batch: 400 (drop), second batch: 200 (success)
        fakeClient.enqueueResponses(
            NetworkResponse(400, emptyMap(), null),
            NetworkResponse(200, emptyMap(), null)
        )
        val dispatcher = Dispatcher(
            options = InitOptions(writeKey = "test-key", ingestionHost = "https://api.example.com", flushIntervalSeconds = 10),
            queue = overflowQueue,
            networkClient = fakeClient,
            circuitBreaker = CircuitBreaker(),
            scope = this,
            config = DispatcherConfig()
        )

        val drained = overflowQueue.drainDiskOverflowToNetwork(dispatcher)

        // Only second batch of 50 counted as drained
        assertEquals(50, drained)
        assertNull(overflowDiskStore.read())

        overflowDir.deleteRecursively()
        unmockkStatic(Log::class)
    }

    @Test
    fun `drain pauses on 429 rate limit`() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val overflowDir = createTempDirectory("metarouter-overflow-429").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val overflowQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        val events = (1..5).map { createTestEvent("overflow-$it") }
        overflowDiskStore.write(QueueSnapshot(version = 1, events = events))

        val fakeClient = FakeNetworkClient()
        fakeClient.nextResponse = NetworkResponse(429, emptyMap(), null)
        val dispatcher = Dispatcher(
            options = InitOptions(writeKey = "test-key", ingestionHost = "https://api.example.com", flushIntervalSeconds = 10),
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
    fun `drain filters expired events by TTL`() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val overflowDir = createTempDirectory("metarouter-overflow-ttl").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val oneDayMs = 24L * 60 * 60 * 1000
        val overflowQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = oneDayMs // 1 day TTL
        )

        // Mix of expired and fresh events
        val expiredTimestamp = formatTimestamp(System.currentTimeMillis() - 2 * oneDayMs) // 2 days ago
        val freshTimestamp = formatTimestamp(System.currentTimeMillis() - 1000) // 1 second ago
        val events = listOf(
            createTestEvent("expired-1", timestamp = expiredTimestamp),
            createTestEvent("expired-2", timestamp = expiredTimestamp),
            createTestEvent("fresh-1", timestamp = freshTimestamp),
            createTestEvent("fresh-2", timestamp = freshTimestamp)
        )
        overflowDiskStore.write(QueueSnapshot(version = 1, events = events))

        val fakeClient = FakeNetworkClient()
        fakeClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        val dispatcher = Dispatcher(
            options = InitOptions(writeKey = "test-key", ingestionHost = "https://api.example.com", flushIntervalSeconds = 10),
            queue = overflowQueue,
            networkClient = fakeClient,
            circuitBreaker = CircuitBreaker(),
            scope = this,
            config = DispatcherConfig()
        )

        val drained = overflowQueue.drainDiskOverflowToNetwork(dispatcher)

        // Only the 2 fresh events should have been sent
        assertEquals(2, drained)
        assertNull(overflowDiskStore.read())

        overflowDir.deleteRecursively()
        unmockkStatic(Log::class)
    }

    @Test
    fun `concurrent drain calls do not duplicate sends`() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val overflowDir = createTempDirectory("metarouter-overflow-concurrent").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val overflowQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        val events = (1..10).map { createTestEvent("overflow-$it") }
        overflowDiskStore.write(QueueSnapshot(version = 1, events = events))

        val fakeClient = FakeNetworkClient()
        fakeClient.nextResponse = NetworkResponse(200, emptyMap(), null)
        val dispatcher = Dispatcher(
            options = InitOptions(writeKey = "test-key", ingestionHost = "https://api.example.com", flushIntervalSeconds = 10),
            queue = overflowQueue,
            networkClient = fakeClient,
            circuitBreaker = CircuitBreaker(),
            scope = this,
            config = DispatcherConfig()
        )

        // Launch two concurrent drain calls
        val drain1 = async { overflowQueue.drainDiskOverflowToNetwork(dispatcher) }
        val drain2 = async { overflowQueue.drainDiskOverflowToNetwork(dispatcher) }

        val result1 = drain1.await()
        val result2 = drain2.await()

        // One should have drained all 10, the other should have returned 0 (guard)
        val totalDrained = result1 + result2
        assertEquals(10, totalDrained)
        assertTrue("One drain should return 0", result1 == 0 || result2 == 0)
        assertNull(overflowDiskStore.read())

        overflowDir.deleteRecursively()
        unmockkStatic(Log::class)
    }

    @Test
    fun `requeueToFront flushes memory queue to overflow disk when at capacity`() {
        val overflowDir = createTempDirectory("metarouter-requeue-overflow").toFile()
        val overflowDiskStore = EventDiskStore(overflowDir)

        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            maxOfflineDiskEvents = 100,
            overflowDiskStore = overflowDiskStore,
            eventTTLMs = 0
        )

        // Simulate: memory queue has 3 new events (enqueued after a drain)
        val newEvents = (1..3).map { createTestEvent("new-$it") }
        newEvents.forEach { smallQueue.enqueue(it) }
        assertEquals(3, smallQueue.size())

        // Now requeue 5 older events (e.g., a failed send retry).
        // Total would be 8, exceeding capacity of 5.
        val requeued = (1..5).map { createTestEvent("older-$it") }
        smallQueue.requeueToFront(requeued)

        // Memory queue should have the 5 requeued events at the front (no drops)
        assertEquals(5, smallQueue.size())
        val drained = smallQueue.drain(10)
        assertEquals(5, drained.size)
        assertEquals("older-1", drained[0].messageId)
        assertEquals("older-5", drained[4].messageId)

        // The 3 new events should be on overflow disk
        val snapshot = overflowDiskStore.read()
        assertNotNull(snapshot)
        assertEquals(3, snapshot!!.events.size)
        assertEquals("new-1", snapshot.events[0].messageId)
        assertEquals("new-3", snapshot.events[2].messageId)

        overflowDir.deleteRecursively()
    }

    @Test
    fun `requeueToFront drops newest when no overflow store at capacity`() {
        // No overflow store — should still drop (backward compat)
        val smallQueue = PersistableEventQueue(
            maxCapacity = 5,
            diskStore = diskStore,
            overflowDiskStore = null,
            eventTTLMs = 0
        )

        val newEvents = (1..3).map { createTestEvent("new-$it") }
        newEvents.forEach { smallQueue.enqueue(it) }

        val requeued = (1..5).map { createTestEvent("older-$it") }
        smallQueue.requeueToFront(requeued)

        // Memory queue should have the 5 requeued events — new ones were dropped
        assertEquals(5, smallQueue.size())
        val drained = smallQueue.drain(10)
        assertEquals("older-1", drained[0].messageId)
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

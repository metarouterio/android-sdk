package com.metarouter.analytics.queue

import com.metarouter.analytics.storage.EventDiskStore
import com.metarouter.analytics.types.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PersistableEventQueueBenchmarkTest {

    private lateinit var tempDir: File
    private lateinit var diskStore: EventDiskStore
    private lateinit var queue: PersistableEventQueue

    @Before
    fun setup() {
        tempDir = createTempDir("metarouter-bench")
        diskStore = EventDiskStore(tempDir)
        PersistableEventQueue.resetRehydrationFlag()
        queue = PersistableEventQueue(
            maxCapacity = 20000,
            diskStore = diskStore,
            flushThresholdEvents = 50000,
            flushThresholdBytes = 100 * 1024 * 1024,
            maxCapacityBytes = 100 * 1024 * 1024
        )
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
        PersistableEventQueue.resetRehydrationFlag()
    }

    @Test
    fun `enqueue 10000 1KB events completes in reasonable time`() {
        val events = (1..10_000).map { create1KBEvent("bench-$it") }

        // Warm up
        repeat(100) { queue.enqueue(events[it]) }
        queue.clear()
        PersistableEventQueue.resetRehydrationFlag()
        queue = PersistableEventQueue(
            maxCapacity = 20000,
            diskStore = diskStore,
            flushThresholdEvents = 50000,
            flushThresholdBytes = 100 * 1024 * 1024,
            maxCapacityBytes = 100 * 1024 * 1024
        )

        val startNanos = System.nanoTime()
        for (event in events) {
            queue.enqueue(event)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0

        println("Enqueue 10,000 1KB events: ${elapsedMs}ms total, ${elapsedMs / 10_000}ms avg")

        // Acceptance: total time under 10 seconds (generous for CI/emulator)
        assertTrue("Enqueue took ${elapsedMs}ms, expected under 10000ms", elapsedMs < 10_000)
    }

    @Test
    fun `flushToDisk 2000 events completes in reasonable time`() {
        val events = (1..2000).map { create1KBEvent("bench-$it") }
        events.forEach { queue.enqueue(it) }

        val startNanos = System.nanoTime()
        queue.flushToDisk()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0

        println("FlushToDisk 2,000 1KB events: ${elapsedMs}ms")
        assertTrue("FlushToDisk took ${elapsedMs}ms, expected under 5000ms", elapsedMs < 5_000)
    }

    private fun create1KBEvent(messageId: String): EnrichedEventPayload {
        val properties = buildMap {
            repeat(20) { i ->
                put("prop_$i", JsonPrimitive("value_${"x".repeat(30)}_$i"))
            }
        }

        return EnrichedEventPayload(
            type = EventType.TRACK,
            event = "Benchmark Event",
            userId = "bench-user-12345678",
            anonymousId = "bench-anon-12345678-1234-1234-1234-123456789abc",
            groupId = null,
            traits = null,
            properties = properties,
            timestamp = "2026-01-01T00:00:00.000Z",
            context = EventContext(
                app = AppContext(name = "BenchApp", version = "1.0.0", build = "100", namespace = "com.bench.app"),
                device = DeviceContext(manufacturer = "Google", model = "Pixel 8", name = "pixel8", type = "android"),
                library = LibraryContext(name = "metarouter-android", version = "1.0.0"),
                locale = "en-US",
                os = OSContext(name = "Android", version = "14"),
                screen = ScreenContext(width = 1080, height = 2400, density = 2.75),
                network = NetworkContext(wifi = true),
                timezone = "America/New_York"
            ),
            messageId = messageId,
            writeKey = "bench-write-key-12345678",
            sentAt = null
        )
    }
}

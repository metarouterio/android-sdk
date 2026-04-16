package com.metarouter.analytics.queue

import com.metarouter.analytics.dispatcher.Dispatcher
import com.metarouter.analytics.dispatcher.ResponseCategory
import com.metarouter.analytics.storage.EventDiskStore
import com.metarouter.analytics.storage.QueueSnapshot
import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.utils.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Event queue with a single disk store for persistence and offline overflow.
 * Memory queue is the hot buffer; disk is the overflow/crash-safety backing.
 *
 * Model:
 * - enqueue() and drain() are memory-only (no disk I/O) in the common case.
 * - When memory queue hits capacity (event count OR byte size), the entire queue
 *   flushes to disk and memory resets to empty. Same triggers swap destination
 *   when the device is offline (see [flushToOfflineStorage]).
 * - flushToDisk() appends current memory queue to disk (crash-safety snapshot).
 * - drainDiskOverflowToNetwork() sends disk events directly to the network via
 *   Dispatcher.sendBatchDirect, bypassing the memory queue.
 *
 * Concurrency:
 * - All memory-queue operations are guarded by `synchronized(this)` via @Synchronized.
 * - Disk operations happen under a separate [diskLock] so the suspend drain can
 *   release it during network calls without blocking enqueue/drain of the memory queue.
 * - Lock order is always `this` -> `diskLock` (never the reverse).
 */
class PersistableEventQueue(
    private val maxCapacity: Int = 2000,
    private val diskStore: EventDiskStore,
    private val flushThresholdEvents: Int = 500,
    private val flushThresholdBytes: Long = 2L * 1024 * 1024,
    private val maxCapacityBytes: Long = 5L * 1024 * 1024,
    private val eventTTLMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val maxOfflineDiskEvents: Int = 10000
) : EventQueueInterface {

    companion object {
        private val hasRehydrated = AtomicBoolean(false)
        private val json = Json { encodeDefaults = true }
        private val timestampFormat = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        /** Write a checkpoint every N successful drain batches for crash safety. */
        private const val DRAIN_CHECKPOINT_BATCHES = 10

        /**
         * Reset the rehydration flag so a subsequent initialize() will rehydrate from disk.
         * Called during SDK reset and in tests.
         */
        internal fun resetRehydrationFlag() {
            hasRehydrated.set(false)
        }
    }

    // Internal memory storage
    private val memoryQueue = ArrayDeque<EnrichedEventPayload>()

    // Tracks estimated serialized size of all events in memory
    private var estimatedBytes: Long = 0L

    // Cache of per-event byte sizes (parallel to memoryQueue order)
    private val eventSizes = ArrayDeque<Long>()

    // Guards against concurrent drainDiskOverflowToNetwork invocations
    private val isDraining = AtomicBoolean(false)

    // True when unsent events exist on disk. Cheap volatile flag used to gate drain attempts.
    // Initialized from file existence (not a full file parse).
    @Volatile
    private var hasOverflowData = try { diskStore.exists() } catch (_: Exception) { false }

    // Lock for disk store access. The suspend drain releases this lock during network calls,
    // so it can't hold the class monitor. Memory queue methods acquire `this` first and
    // `diskLock` second (when they touch disk). Drain acquires only `diskLock`. No deadlock.
    private val diskLock = Any()

    @Synchronized
    override fun size(): Int = memoryQueue.size

    /**
     * Enqueue an event to memory.
     * When at capacity (event count or byte size), flushes the entire memory queue
     * to disk. Events are only dropped if capacity is hit and disk operations fail.
     *
     * If [maxOfflineDiskEvents] is 0, disk persistence is disabled and the queue
     * acts as a pure in-memory ring buffer: at capacity, the oldest event is dropped.
     */
    @Synchronized
    override fun enqueue(event: EnrichedEventPayload) {
        val eventSize = estimateEventSize(event)

        if (maxOfflineDiskEvents == 0) {
            dropOldestUntilFits(eventSize)
            memoryQueue.addLast(event)
            eventSizes.addLast(eventSize)
            estimatedBytes += eventSize
            return
        }

        // Over byte capacity: flush memory queue to disk
        if (estimatedBytes + eventSize > maxCapacityBytes && memoryQueue.isNotEmpty()) {
            flushMemoryToDiskInternal()
        }

        // At event count capacity: flush memory queue to disk
        if (memoryQueue.size >= maxCapacity) {
            flushMemoryToDiskInternal()
        }

        memoryQueue.addLast(event)
        eventSizes.addLast(eventSize)
        estimatedBytes += eventSize
    }

    /**
     * Drain events from memory. No disk I/O. Updates byte size tracking.
     */
    @Synchronized
    override fun drain(max: Int): List<EnrichedEventPayload> {
        val n = minOf(max, memoryQueue.size)
        return (0 until n).map {
            val event = memoryQueue.removeFirst()
            val size = eventSizes.removeFirst()
            estimatedBytes -= size
            event
        }
    }

    /**
     * Requeue events to front (for retry after a failed send).
     * When at capacity, flushes existing memory queue to disk to make room. The requeued
     * (older) events take priority at the front since they were drained first and a send
     * already started for them. If the requeue batch itself exceeds capacity mid-loop,
     * we flush again so no requeued events get dropped.
     */
    @Synchronized
    override fun requeueToFront(events: List<EnrichedEventPayload>) {
        if (maxOfflineDiskEvents == 0) {
            // In-memory-only mode: at cap, drop newest (back of queue) so the
            // requeued retry events at the front are preserved.
            events.asReversed().forEach { event ->
                val eventSize = estimateEventSize(event)
                dropNewestUntilFits(eventSize)
                memoryQueue.addFirst(event)
                eventSizes.addFirst(eventSize)
                estimatedBytes += eventSize
            }
            return
        }

        // Initial flush: if adding these events would exceed capacity, flush current memory first.
        if ((memoryQueue.size + events.size > maxCapacity ||
             estimatedBytes + events.sumOf { estimateEventSize(it) } > maxCapacityBytes) &&
            memoryQueue.isNotEmpty()) {
            flushMemoryToDiskInternal()
        }

        events.asReversed().forEach { event ->
            val eventSize = estimateEventSize(event)

            // Mid-loop capacity: the requeue batch itself exceeds capacity after earlier iterations
            // filled the queue. Flush to disk instead of dropping requeued events.
            if (estimatedBytes + eventSize > maxCapacityBytes && memoryQueue.isNotEmpty()) {
                flushMemoryToDiskInternal()
            }
            if (memoryQueue.size >= maxCapacity) {
                flushMemoryToDiskInternal()
            }

            memoryQueue.addFirst(event)
            eventSizes.addFirst(eventSize)
            estimatedBytes += eventSize
        }
    }

    /**
     * Clear all events from memory and delete the disk file.
     */
    @Synchronized
    override fun clear() {
        val count = memoryQueue.size
        memoryQueue.clear()
        eventSizes.clear()
        estimatedBytes = 0L
        synchronized(diskLock) {
            deleteDiskStore()
        }
        if (count > 0) {
            Logger.log("Cleared $count events from queue and deleted disk snapshot")
        }
    }

    /**
     * Flush memory queue to disk when the dispatcher triggers a flush while offline.
     * Returns true if events were flushed to disk. No-op (returns false) when
     * [maxOfflineDiskEvents] is 0.
     */
    @Synchronized
    override fun flushToOfflineStorage(): Boolean {
        if (maxOfflineDiskEvents == 0) return false
        if (memoryQueue.isEmpty()) return false
        flushMemoryToDiskInternal()
        return true
    }

    /**
     * Best-effort crash-safety flush: appends current memory queue to disk.
     * Called on app background / onTrimMemory. No-op when [maxOfflineDiskEvents] is 0.
     */
    @Synchronized
    fun flushToDisk() {
        if (maxOfflineDiskEvents == 0) return
        if (memoryQueue.isEmpty()) return
        flushMemoryToDiskInternal()
    }

    /**
     * Called once per process on startup. With the consolidated disk store, events
     * persisted across sessions stay on disk and drain to the network via
     * [drainDiskOverflowToNetwork]. Memory queue starts empty — no loading needed.
     * [hasOverflowData] reflects whether disk has unsent events (set in constructor).
     */
    @Synchronized
    fun rehydrate() {
        if (!hasRehydrated.compareAndSet(false, true)) {
            Logger.log("Rehydration already completed this process - skipping")
            return
        }
        if (hasOverflowData) {
            Logger.log("Unsent events present on disk — will drain on next online transition")
        }
    }

    /**
     * Check if the flush threshold has been reached (either event count or byte size).
     */
    @Synchronized
    fun shouldFlushToDisk(): Boolean {
        return memoryQueue.size >= flushThresholdEvents || estimatedBytes >= flushThresholdBytes
    }

    /**
     * Get the estimated total byte size of events in memory.
     */
    @Synchronized
    fun estimatedSizeBytes(): Long = estimatedBytes

    /**
     * Check if there is unsent data on disk worth draining.
     * Cheap check — reads a volatile flag, no disk I/O.
     */
    fun hasOverflowData(): Boolean = hasOverflowData

    // ===== Offline Overflow =====

    /**
     * Drain disk events directly to the network in batches via [Dispatcher.sendBatchDirect].
     * Does NOT load events into the memory queue. Called on offline->online transitions
     * and after successful online flushes via onFlushComplete.
     *
     * Reads the entire disk file once, deletes it, then iterates batches in-memory.
     * Writes a checkpoint every [DRAIN_CHECKPOINT_BATCHES] successful batches for crash safety.
     * On failure, writes remaining events back to disk.
     *
     * @return number of events successfully drained
     */
    suspend fun drainDiskOverflowToNetwork(dispatcher: Dispatcher): Int {
        // Bail early if offline — no point burning network call timeouts.
        if (dispatcher.isNetworkPaused()) return 0
        if (!isDraining.compareAndSet(false, true)) return 0

        var totalDrained = 0
        var currentBatchSize = 100

        try {
            // Read entire file once and delete — O(n) instead of O(n²)
            val allEvents = synchronized(diskLock) {
                val snapshot = diskStore.read() ?: return 0
                deleteDiskStore()
                snapshot.events
            }

            // Filter expired events
            val now = System.currentTimeMillis()
            val events = if (eventTTLMs > 0) {
                allEvents.filter { !isExpired(it, now) }
            } else {
                allEvents
            }

            if (events.isEmpty()) return 0

            // Track position via index — avoids O(n) removeAt(0) on each batch
            var offset = 0
            var batchesSinceCheckpoint = 0
            fun remaining() = events.subList(offset, events.size)

            while (offset < events.size) {
                val batchEnd = minOf(offset + currentBatchSize, events.size)
                val batch = events.subList(offset, batchEnd)

                val response = dispatcher.sendBatchDirect(batch)

                if (response == null) {
                    writeCheckpoint(remaining())
                    Logger.warn("Offline overflow drain paused — network error ($totalDrained drained so far)")
                    return totalDrained
                }

                when (ResponseCategory.from(response.statusCode)) {
                    ResponseCategory.SUCCESS -> {
                        offset += batch.size
                        totalDrained += batch.size
                        batchesSinceCheckpoint++
                        if (currentBatchSize < 100) {
                            currentBatchSize = minOf(currentBatchSize * 2, 100)
                        }
                        // Periodic checkpoint for crash safety
                        if (batchesSinceCheckpoint >= DRAIN_CHECKPOINT_BATCHES && offset < events.size) {
                            writeCheckpoint(remaining())
                            batchesSinceCheckpoint = 0
                        }
                    }
                    ResponseCategory.PAYLOAD_TOO_LARGE -> {
                        if (currentBatchSize > 1) {
                            currentBatchSize = maxOf(1, currentBatchSize / 2)
                            Logger.warn("Overflow drain: payload too large (413) — reduced batch to $currentBatchSize")
                        } else {
                            Logger.warn("Overflow drain: dropping oversized event at batchSize=1 (messageId: ${batch.first().messageId})")
                            offset++
                        }
                    }
                    ResponseCategory.FATAL_CONFIG -> {
                        Logger.error("Overflow drain: fatal config error ${response.statusCode} — discarding ${events.size - offset} overflow events")
                        // Notify the dispatcher so the main pipeline also shuts down. Matches
                        // the main flush path's handling of auth/config failures.
                        dispatcher.onFatalConfigError?.invoke(response.statusCode)
                        return totalDrained
                    }
                    ResponseCategory.CLIENT_ERROR -> {
                        Logger.warn("Overflow drain: client error ${response.statusCode} — dropping batch of ${batch.size}")
                        offset += batch.size
                    }
                    ResponseCategory.SERVER_ERROR, ResponseCategory.RATE_LIMITED -> {
                        writeCheckpoint(remaining())
                        Logger.warn("Offline overflow drain paused — ${response.statusCode} ($totalDrained drained so far)")
                        return totalDrained
                    }
                }
            }

            // All events drained successfully
            synchronized(diskLock) { deleteDiskStore() }
            Logger.log("Offline overflow disk drain complete ($totalDrained events)")
            return totalDrained
        } finally {
            isDraining.set(false)
        }
    }

    /**
     * Write remaining events back to the disk store as a checkpoint.
     * Used during drain for crash safety and on failure to preserve unsent events.
     */
    private fun writeCheckpoint(events: List<EnrichedEventPayload>) {
        if (events.isEmpty()) return
        synchronized(diskLock) {
            diskStore.write(QueueSnapshot(version = 1, events = events))
            hasOverflowData = true
        }
    }


    // ===== Internal =====

    /**
     * Flush all events from memory queue to disk. Appends to any existing disk data,
     * enforces [maxOfflineDiskEvents] cap, and resets the memory queue.
     * Must be called while holding `synchronized(this)`.
     *
     * Note: performs disk I/O (read + write) while holding both `this` and `diskLock`.
     * Local disk I/O is sub-millisecond for typical event sizes, so this is acceptable
     * even on the enqueue hot path. Revisit if profiling shows contention.
     */
    private fun flushMemoryToDiskInternal() {
        if (memoryQueue.isEmpty()) return

        val batch = memoryQueue.toList()
        memoryQueue.clear()
        eventSizes.clear()
        estimatedBytes = 0L

        synchronized(diskLock) {
            val existing = try {
                diskStore.read()?.events ?: emptyList()
            } catch (e: Exception) {
                Logger.warn("Failed to read disk store during flush: ${e.message}")
                emptyList()
            }

            var combined = existing + batch
            if (combined.size > maxOfflineDiskEvents) {
                val dropCount = combined.size - maxOfflineDiskEvents
                combined = combined.drop(dropCount)
                Logger.warn("Offline disk cap reached — dropped $dropCount oldest events")
            }

            diskStore.write(QueueSnapshot(version = 1, events = combined))
            hasOverflowData = true
            Logger.log("Flushed ${batch.size} events to disk (total on disk: ${combined.size})")
        }
    }

    /**
     * In-memory-only mode: drop oldest events until the new event of [newEventSize]
     * fits under both the event count and byte capacity caps.
     * Must be called while holding `synchronized(this)`.
     */
    private fun dropOldestUntilFits(newEventSize: Long) {
        var dropped = 0
        while (memoryQueue.isNotEmpty() &&
               (memoryQueue.size >= maxCapacity ||
                estimatedBytes + newEventSize > maxCapacityBytes)) {
            memoryQueue.removeFirst()
            estimatedBytes -= eventSizes.removeFirst()
            dropped++
        }
        if (dropped > 0) {
            Logger.warn("In-memory queue cap reached — dropped $dropped oldest event(s) (maxOfflineDiskEvents=0)")
        }
    }

    /**
     * In-memory-only mode (requeue path): drop newest events from the back so that
     * older requeued retries at the front are preserved.
     * Must be called while holding `synchronized(this)`.
     */
    private fun dropNewestUntilFits(newEventSize: Long) {
        var dropped = 0
        while (memoryQueue.isNotEmpty() &&
               (memoryQueue.size >= maxCapacity ||
                estimatedBytes + newEventSize > maxCapacityBytes)) {
            memoryQueue.removeLast()
            estimatedBytes -= eventSizes.removeLast()
            dropped++
        }
        if (dropped > 0) {
            Logger.warn("In-memory queue cap reached during requeue — dropped $dropped newest event(s) (maxOfflineDiskEvents=0)")
        }
    }

    /** Delete the disk file and clear the hasOverflowData flag. */
    private fun deleteDiskStore() {
        diskStore.delete()
        hasOverflowData = false
    }

    private fun estimateEventSize(event: EnrichedEventPayload): Long {
        return try {
            json.encodeToString(event).length.toLong()
        } catch (e: Exception) {
            Logger.warn("Failed to estimate event size, using 512B fallback: ${e.message}")
            512L
        }
    }

    private fun isExpired(event: EnrichedEventPayload, now: Long): Boolean {
        return try {
            val eventTime = timestampFormat.get()!!.parse(event.timestamp)?.time ?: return false
            (now - eventTime) > eventTTLMs
        } catch (e: Exception) {
            false
        }
    }
}

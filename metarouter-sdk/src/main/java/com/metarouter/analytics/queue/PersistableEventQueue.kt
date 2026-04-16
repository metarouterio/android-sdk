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
 * Event queue with disk persistence as a safety net.
 * Uses composition: wraps an internal memory queue and adds byte tracking + disk I/O.
 *
 * Write-behind model:
 * - enqueue() and drain() are memory-only (no disk I/O)
 * - flushToDisk() writes a full snapshot of current memory state
 * - rehydrate() loads events from disk once per process lifetime
 *
 * Capacity model:
 * - Single shared cap: [maxCapacity] events OR [maxCapacityBytes] total, whichever is hit first
 * - Flush threshold: [flushThresholdEvents] events OR [flushThresholdBytes], whichever is hit first
 *
 * Offline overflow model:
 * - When offline, flush triggers write the entire memory queue to the overflow disk store
 *   instead of sending to the network. Same triggers, swapped destination.
 * - When the queue hits capacity (online or offline), it flushes to overflow disk rather than dropping.
 * - On online transition, overflow events drain from disk directly to network.
 */
class PersistableEventQueue(
    private val maxCapacity: Int = 2000,
    private val diskStore: EventDiskStore,
    private val flushThresholdEvents: Int = 500,
    private val flushThresholdBytes: Long = 2L * 1024 * 1024,
    private val maxCapacityBytes: Long = 5L * 1024 * 1024,
    private val eventTTLMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val maxOfflineDiskEvents: Int = 10000,
    private val overflowDiskStore: EventDiskStore? = null
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

    // Lightweight flag: true when overflow data exists on disk.
    // Set when events are flushed to overflow disk, cleared when drain empties the store.
    // Avoids unnecessary disk reads in onFlushComplete when no overflow exists.
    // Initialized by checking if overflow file exists from a previous session.
    @Volatile
    private var hasOverflowData = try { overflowDiskStore?.read() != null } catch (_: Exception) { false }

    // Lock for overflow disk store access — shared between synchronized queue methods
    // (which hold `this` monitor) and the unsynchronized drain coroutine.
    // Queue methods call flushToOverflowDiskInternal under `synchronized(this)` which
    // also acquires overflowLock. Drain acquires only overflowLock (not `this`), so
    // there is no deadlock risk — the lock order is always: this -> overflowLock.
    private val overflowLock = Any()

    @Synchronized
    override fun size(): Int = memoryQueue.size

    /**
     * Enqueue an event to memory.
     * When at capacity (event count or byte size), flushes to overflow disk if available,
     * otherwise drops oldest events.
     */
    @Synchronized
    override fun enqueue(event: EnrichedEventPayload) {
        val eventSize = estimateEventSize(event)

        // Over byte capacity: flush to overflow disk if available, otherwise drop oldest
        if (estimatedBytes + eventSize > maxCapacityBytes && memoryQueue.isNotEmpty()) {
            if (overflowDiskStore != null) {
                flushToOverflowDiskInternal()
            } else {
                while (estimatedBytes + eventSize > maxCapacityBytes && memoryQueue.isNotEmpty()) {
                    dropOldest("byte capacity")
                }
            }
        }

        // At event count capacity: flush to overflow disk if available, otherwise drop oldest
        if (memoryQueue.size >= maxCapacity) {
            if (overflowDiskStore != null) {
                flushToOverflowDiskInternal()
            } else {
                dropOldest("event count capacity")
            }
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
     * When at capacity, flushes existing memory queue to overflow disk if available,
     * otherwise drops newest events to make room. The requeued events always take
     * priority at the front since they were drained first (older) and a send
     * already started for them.
     */
    @Synchronized
    override fun requeueToFront(events: List<EnrichedEventPayload>) {
        // If adding these events would overflow capacity and we have an overflow store,
        // flush the current memory queue to disk first. The newer events go to disk;
        // the requeued (older) events take their place at the front.
        if (overflowDiskStore != null &&
            (memoryQueue.size + events.size > maxCapacity ||
             estimatedBytes + events.sumOf { estimateEventSize(it) } > maxCapacityBytes) &&
            memoryQueue.isNotEmpty()) {
            flushToOverflowDiskInternal()
        }

        events.asReversed().forEach { event ->
            val eventSize = estimateEventSize(event)

            // Edge case: requeue batch alone exceeds byte capacity.
            // Drop newest to make room (no overflow store available, or batch too big).
            while (estimatedBytes + eventSize > maxCapacityBytes && memoryQueue.isNotEmpty()) {
                val removedSize = eventSizes.removeLastOrNull() ?: 0L
                memoryQueue.removeLastOrNull()?.let {
                    estimatedBytes -= removedSize
                    Logger.warn("Queue byte capacity reached during requeue - dropped newest event (messageId: ${it.messageId})")
                }
            }

            // Edge case: requeue batch alone exceeds event count capacity.
            if (memoryQueue.size >= maxCapacity) {
                val removedSize = eventSizes.removeLastOrNull() ?: 0L
                memoryQueue.removeLastOrNull()?.let {
                    estimatedBytes -= removedSize
                    Logger.warn("Queue event capacity reached during requeue - dropped newest event (messageId: ${it.messageId})")
                }
            }

            memoryQueue.addFirst(event)
            eventSizes.addFirst(eventSize)
            estimatedBytes += eventSize
        }
    }

    /**
     * Clear all events from memory and delete disk snapshots.
     */
    @Synchronized
    override fun clear() {
        val count = memoryQueue.size
        memoryQueue.clear()
        eventSizes.clear()
        estimatedBytes = 0L
        diskStore.delete()
        synchronized(overflowLock) {
            deleteOverflowStore()
        }
        if (count > 0) {
            Logger.log("Cleared $count events from queue and deleted disk snapshots")
        }
    }

    /**
     * Flush all events to offline overflow disk storage.
     * Called by the dispatcher when a flush is triggered but the device is offline.
     * Drains the entire memory queue and appends to the overflow disk store.
     */
    @Synchronized
    override fun flushToOfflineStorage(): Boolean {
        if (overflowDiskStore == null || memoryQueue.isEmpty()) return false
        flushToOverflowDiskInternal()
        return true
    }

    /**
     * Flush current memory state to disk as a full overwrite snapshot.
     * Skips the write and deletes any existing snapshot if the queue is empty.
     */
    @Synchronized
    fun flushToDisk() {
        if (memoryQueue.isEmpty()) {
            diskStore.delete()
            return
        }
        val events = memoryQueue.toList()
        val snapshot = QueueSnapshot(version = 1, events = events)
        diskStore.write(snapshot)
    }

    /**
     * Rehydrate events from disk into memory. Only executes once per process.
     * Loaded events count toward capacity. Excess events are trimmed (keep newest).
     * Deletes the disk file after successful load.
     */
    @Synchronized
    fun rehydrate() {
        if (!hasRehydrated.compareAndSet(false, true)) {
            Logger.log("Rehydration already completed this process - skipping")
            return
        }

        val snapshot = diskStore.read() ?: return

        var events = snapshot.events
        if (events.isEmpty()) {
            diskStore.delete()
            return
        }

        // Drop events older than TTL (keep newest)
        if (eventTTLMs > 0) {
            val now = System.currentTimeMillis()
            val beforeTTL = events.size
            events = events.filter { !isExpired(it, now) }
            val dropped = beforeTTL - events.size
            if (dropped > 0) {
                Logger.warn("Rehydration: dropped $dropped event(s) older than ${eventTTLMs / (24 * 60 * 60 * 1000)}d TTL")
            }
        }

        // Trim to event count capacity (keep newest)
        if (events.size > maxCapacity) {
            Logger.warn("Disk snapshot has ${events.size} events, trimming to $maxCapacity (keeping newest)")
            events = events.drop(events.size - maxCapacity)
        }

        // Trim by byte capacity (keep newest)
        var totalBytes = 0L
        val sizedEvents = events.map { it to estimateEventSize(it) }
        val fittingEvents = mutableListOf<Pair<EnrichedEventPayload, Long>>()

        for ((event, size) in sizedEvents.asReversed()) {
            if (totalBytes + size > maxCapacityBytes) break
            totalBytes += size
            fittingEvents.add(event to size)
        }
        fittingEvents.reverse()

        if (fittingEvents.size < events.size) {
            Logger.warn("Disk snapshot exceeds byte capacity, trimmed from ${events.size} to ${fittingEvents.size} events")
        }

        // Load into memory
        for ((event, size) in fittingEvents) {
            memoryQueue.addLast(event)
            eventSizes.addLast(size)
            estimatedBytes += size
        }

        Logger.log("Rehydrated ${fittingEvents.size} events from disk")
        diskStore.delete()
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
     * Check if there is overflow data on disk worth draining.
     * Cheap check — no disk I/O, just reads a volatile flag.
     */
    fun hasOverflowData(): Boolean = hasOverflowData

    // ===== Offline Overflow =====

    /**
     * Drain overflow events directly from disk to network in batches.
     * Does NOT load events into the memory queue — sends via dispatcher's direct path.
     * Called on offline->online transition as an independent flush pipeline.
     *
     * Reads the entire overflow file once into memory, then drains batches from the
     * in-memory list. Writes a checkpoint to disk every [DRAIN_CHECKPOINT_BATCHES]
     * successful batches for crash safety. On failure, writes remaining events back
     * to disk and returns.
     *
     * @return number of events successfully drained
     */
    suspend fun drainDiskOverflowToNetwork(dispatcher: Dispatcher): Int {
        val store = overflowDiskStore ?: return 0
        if (!isDraining.compareAndSet(false, true)) return 0

        var totalDrained = 0
        var currentBatchSize = 100

        try {
            // Read entire file once and delete — O(n) instead of O(n²)
            val allEvents = synchronized(overflowLock) {
                val snapshot = store.read() ?: return 0
                deleteOverflowStore()
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
                    writeOverflowCheckpoint(store, remaining())
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
                            writeOverflowCheckpoint(store, remaining())
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
                        return totalDrained
                    }
                    ResponseCategory.CLIENT_ERROR -> {
                        Logger.warn("Overflow drain: client error ${response.statusCode} — dropping batch of ${batch.size}")
                        offset += batch.size
                    }
                    ResponseCategory.SERVER_ERROR, ResponseCategory.RATE_LIMITED -> {
                        writeOverflowCheckpoint(store, remaining())
                        Logger.warn("Offline overflow drain paused — ${response.statusCode} ($totalDrained drained so far)")
                        return totalDrained
                    }
                }
            }

            // All events drained successfully
            synchronized(overflowLock) { deleteOverflowStore() }
            Logger.log("Offline overflow disk drain complete ($totalDrained events)")
            return totalDrained
        } finally {
            isDraining.set(false)
        }
    }

    /**
     * Write remaining events back to the overflow disk store as a checkpoint.
     * Used during drain for crash safety and on failure to preserve unsent events.
     */
    private fun writeOverflowCheckpoint(store: EventDiskStore, events: List<EnrichedEventPayload>) {
        if (events.isEmpty()) return
        synchronized(overflowLock) {
            store.write(QueueSnapshot(version = 1, events = events))
            hasOverflowData = true
        }
    }


    // ===== Internal =====

    /**
     * Flush all events from memory queue to the overflow disk store.
     * Appends to existing overflow events on disk. Enforces maxOfflineDiskEvents cap.
     * Resets the memory queue to empty.
     * Must be called within a synchronized block.
     *
     * Note: performs disk I/O (read + write) while holding the synchronized lock.
     * This blocks enqueue/drain during the write. Acceptable because local disk I/O
     * is fast (sub-ms for typical event sizes), but worth revisiting if profiling
     * shows contention under extreme throughput.
     */
    private fun flushToOverflowDiskInternal() {
        val store = overflowDiskStore ?: return
        if (memoryQueue.isEmpty()) return

        val batch = memoryQueue.toList()
        memoryQueue.clear()
        eventSizes.clear()
        estimatedBytes = 0L

        synchronized(overflowLock) {
            val existing = try {
                store.read()?.events ?: emptyList()
            } catch (e: Exception) {
                Logger.warn("Failed to read overflow disk store: ${e.message}")
                emptyList()
            }

            var combined = existing + batch
            if (combined.size > maxOfflineDiskEvents) {
                val dropCount = combined.size - maxOfflineDiskEvents
                combined = combined.drop(dropCount)
                Logger.warn("Offline overflow disk cap reached — dropped $dropCount oldest events")
            }

            store.write(QueueSnapshot(version = 1, events = combined))
            hasOverflowData = true
            Logger.log("Flushed ${batch.size} events to offline disk (total on disk: ${combined.size})")
        }
    }

    /** Delete the overflow disk file and clear the hasOverflowData flag. */
    private fun deleteOverflowStore() {
        overflowDiskStore?.delete()
        hasOverflowData = false
    }

    private fun dropOldest(reason: String) {
        memoryQueue.removeFirstOrNull()?.let { dropped ->
            val size = eventSizes.removeFirstOrNull() ?: 0L
            estimatedBytes -= size
            Logger.warn("Queue $reason reached - dropped oldest event (messageId: ${dropped.messageId})")
        }
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

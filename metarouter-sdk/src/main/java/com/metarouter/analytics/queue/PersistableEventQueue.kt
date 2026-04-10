package com.metarouter.analytics.queue

import com.metarouter.analytics.dispatcher.Dispatcher
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
 */
class PersistableEventQueue(
    private val maxCapacity: Int = 2000,
    private val diskStore: EventDiskStore,
    private val flushThresholdEvents: Int = 500,
    private val flushThresholdBytes: Long = 2L * 1024 * 1024,
    private val maxCapacityBytes: Long = 5L * 1024 * 1024,
    private val eventTTLMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val maxOfflineDiskEvents: Int = 10000,
    private val overflowDiskStore: EventDiskStore? = null,
    private val overflowBufferBatchThreshold: Int = 100
) : EventQueueInterface {

    companion object {
        private val hasRehydrated = AtomicBoolean(false)
        private val json = Json { encodeDefaults = true }
        private val timestampFormat = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

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

    // Offline overflow state
    @Volatile
    private var offlineOverflowEnabled = false
    private val overflowBuffer = ArrayDeque<EnrichedEventPayload>()

    @Synchronized
    override fun size(): Int = memoryQueue.size

    /**
     * Enqueue an event to memory. No disk I/O.
     * Drops oldest events if event count or byte capacity is exceeded.
     */
    @Synchronized
    override fun enqueue(event: EnrichedEventPayload) {
        val eventSize = estimateEventSize(event)

        // Drop oldest while over byte capacity (before adding)
        while (estimatedBytes + eventSize > maxCapacityBytes && memoryQueue.isNotEmpty()) {
            dropOldest("byte capacity")
        }

        // Drop oldest if at event count capacity
        if (memoryQueue.size >= maxCapacity) {
            if (offlineOverflowEnabled && overflowDiskStore != null) {
                // Overflow to buffer instead of dropping
                val oldest = memoryQueue.removeFirst()
                val size = eventSizes.removeFirst()
                estimatedBytes -= size
                bufferOverflow(oldest)
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
     * Requeue events to front. Updates byte size tracking.
     */
    @Synchronized
    override fun requeueToFront(events: List<EnrichedEventPayload>) {
        events.asReversed().forEach { event ->
            val eventSize = estimateEventSize(event)

            // Drop newest while over byte capacity
            while (estimatedBytes + eventSize > maxCapacityBytes && memoryQueue.isNotEmpty()) {
                val removedSize = eventSizes.removeLastOrNull() ?: 0L
                memoryQueue.removeLastOrNull()?.let {
                    estimatedBytes -= removedSize
                    Logger.warn("Queue byte capacity reached during requeue - dropped newest event (messageId: ${it.messageId})")
                }
            }

            // Drop newest if at event count capacity
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
     * Clear all events from memory and delete the disk snapshot.
     */
    @Synchronized
    override fun clear() {
        val count = memoryQueue.size
        memoryQueue.clear()
        eventSizes.clear()
        estimatedBytes = 0L
        overflowBuffer.clear()
        diskStore.delete()
        overflowDiskStore?.delete()
        if (count > 0) {
            Logger.log("Cleared $count events from queue and deleted disk snapshots")
        }
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

    // ===== Offline Overflow =====

    /**
     * Enable or disable offline overflow mode. When enabled and memory queue is full,
     * oldest events overflow to buffer (then disk) instead of being dropped.
     */
    @Synchronized
    fun setOfflineOverflowEnabled(enabled: Boolean) {
        offlineOverflowEnabled = enabled
    }

    /**
     * Buffer an evicted event for batched disk write.
     * Flushes buffer to disk when batch threshold reached.
     * Must be called within a synchronized block.
     */
    private fun bufferOverflow(event: EnrichedEventPayload) {
        overflowBuffer.addLast(event)
        if (overflowBuffer.size >= overflowBufferBatchThreshold) {
            flushOverflowBufferToDiskInternal()
        }
    }

    /**
     * Flush the in-memory overflow buffer to the overflow disk store.
     * Called when buffer reaches batch threshold, on app backgrounding,
     * or on offline->online transition before draining.
     */
    @Synchronized
    fun flushOverflowBufferToDisk() {
        flushOverflowBufferToDiskInternal()
    }

    /**
     * Internal implementation — assumes lock is already held or called within synchronized block.
     */
    private fun flushOverflowBufferToDiskInternal() {
        val store = overflowDiskStore ?: return
        if (overflowBuffer.isEmpty()) return

        val batch = overflowBuffer.toList()
        overflowBuffer.clear()

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
        Logger.log("Flushed ${batch.size} overflow events to disk (total on disk: ${combined.size})")
    }

    /**
     * Drain overflow events directly from disk to network in batches.
     * Does NOT load events into the memory queue — sends via dispatcher's direct path.
     * Called on offline->online transition as an independent flush pipeline.
     *
     * @return number of events successfully drained
     */
    suspend fun drainDiskOverflowToNetwork(dispatcher: Dispatcher): Int {
        val store = overflowDiskStore ?: return 0

        // First, flush any remaining buffer to disk
        synchronized(this) {
            flushOverflowBufferToDiskInternal()
        }

        var totalDrained = 0

        while (true) {
            val snapshot = store.read() ?: return totalDrained
            val now = System.currentTimeMillis()
            val events = if (eventTTLMs > 0) {
                snapshot.events.filter { !isExpired(it, now) }
            } else {
                snapshot.events
            }

            if (events.isEmpty()) {
                store.delete()
                return totalDrained
            }

            // Take a batch from the front (oldest first)
            val batchSize = minOf(100, events.size)
            val batch = events.take(batchSize)
            val remaining = events.drop(batchSize)

            // Send batch directly to network via dispatcher's HTTP path
            val success = dispatcher.sendBatchDirect(batch)
            if (success) {
                totalDrained += batch.size
                if (remaining.isEmpty()) {
                    store.delete()
                    Logger.log("Offline overflow disk drain complete ($totalDrained events)")
                    return totalDrained
                } else {
                    store.write(QueueSnapshot(version = 1, events = remaining))
                }
            } else {
                // Network failed — stop draining, will retry on next online transition
                Logger.warn("Offline overflow drain paused — network send failed ($totalDrained drained so far)")
                return totalDrained
            }
        }
    }

    /**
     * Get count of events in the offline overflow buffer + disk store.
     */
    @Synchronized
    fun offlineOverflowCount(): Int {
        val diskCount = try {
            overflowDiskStore?.read()?.events?.size ?: 0
        } catch (e: Exception) {
            0
        }
        return overflowBuffer.size + diskCount
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

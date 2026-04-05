package com.metarouter.analytics.queue

import com.metarouter.analytics.storage.EventDiskStore
import com.metarouter.analytics.storage.QueueSnapshot
import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.utils.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extension of EventQueue that adds disk persistence as a safety net.
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
    private val maxCapacityBytes: Long = 5L * 1024 * 1024
) : EventQueue(maxCapacity) {

    companion object {
        private val hasRehydrated = AtomicBoolean(false)
        private val json = Json { encodeDefaults = true }

        /**
         * Reset the rehydration flag. For testing only.
         */
        internal fun resetRehydrationFlag() {
            hasRehydrated.set(false)
        }
    }

    // Tracks estimated serialized size of all events in memory
    private var estimatedBytes: Long = 0L

    // Cache of per-event byte sizes (parallel to queue order)
    private val eventSizes = ArrayDeque<Long>()

    /**
     * Enqueue an event to memory. No disk I/O.
     * Drops oldest events if event count or byte capacity is exceeded.
     */
    @Synchronized
    override fun enqueue(event: EnrichedEventPayload) {
        val eventSize = estimateEventSize(event)

        // Drop oldest while over byte capacity (before adding)
        while (estimatedBytes + eventSize > maxCapacityBytes && queue.isNotEmpty()) {
            dropOldest("byte capacity")
        }

        // Drop oldest if at event count capacity
        if (queue.size >= maxCapacity) {
            dropOldest("event count capacity")
        }

        queue.addLast(event)
        eventSizes.addLast(eventSize)
        estimatedBytes += eventSize
    }

    /**
     * Drain events from memory. No disk I/O. Updates byte size tracking.
     */
    @Synchronized
    override fun drain(max: Int): List<EnrichedEventPayload> {
        val n = minOf(max, queue.size)
        return (0 until n).map {
            val event = queue.removeFirst()
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

            if (queue.size >= maxCapacity) {
                val removedSize = eventSizes.removeLastOrNull() ?: 0L
                queue.removeLastOrNull()?.let {
                    estimatedBytes -= removedSize
                    Logger.warn("Queue at capacity during requeue - dropped newest event (messageId: ${it.messageId})")
                }
            }

            queue.addFirst(event)
            eventSizes.addFirst(eventSize)
            estimatedBytes += eventSize
        }
    }

    /**
     * Clear all events from memory and delete the disk snapshot.
     */
    @Synchronized
    override fun clear() {
        val count = queue.size
        queue.clear()
        eventSizes.clear()
        estimatedBytes = 0L
        diskStore.delete()
        if (count > 0) {
            Logger.log("Cleared $count events from queue and deleted disk snapshot")
        }
    }

    /**
     * Flush current memory state to disk as a full overwrite snapshot.
     * Skips the write and deletes any existing snapshot if the queue is empty.
     */
    @Synchronized
    fun flushToDisk() {
        if (queue.isEmpty()) {
            diskStore.delete()
            return
        }
        val events = queue.toList()
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

        // Load into queue
        for ((event, size) in fittingEvents) {
            queue.addLast(event)
            eventSizes.addLast(size)
            estimatedBytes += size
        }

        Logger.log("Rehydrated ${fittingEvents.size} events from disk ($estimatedBytes bytes estimated)")
        diskStore.delete()
    }

    /**
     * Check if the flush threshold has been reached (either event count or byte size).
     */
    @Synchronized
    fun shouldFlushToDisk(): Boolean {
        return queue.size >= flushThresholdEvents || estimatedBytes >= flushThresholdBytes
    }

    /**
     * Get the estimated total byte size of events in memory.
     */
    @Synchronized
    fun estimatedSizeBytes(): Long = estimatedBytes

    private fun dropOldest(reason: String) {
        queue.removeFirstOrNull()?.let { dropped ->
            val size = eventSizes.removeFirstOrNull() ?: 0L
            estimatedBytes -= size
            Logger.warn("Queue $reason reached - dropped oldest event (messageId: ${dropped.messageId})")
        }
    }

    private fun estimateEventSize(event: EnrichedEventPayload): Long {
        return try {
            json.encodeToString(event).length.toLong()
        } catch (e: Exception) {
            512L
        }
    }
}

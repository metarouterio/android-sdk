package com.metarouter.analytics.queue

import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.utils.Logger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe FIFO queue for event management with overflow handling.
 *
 * @property maxCapacity Maximum number of events to hold in memory
 */
class EventQueue(private val maxCapacity: Int = 2000) {


    private val queue = ArrayDeque<EnrichedEventPayload>()


    private val lock = ReentrantReadWriteLock()

    /**
     * Get the current number of queued events.
     * Thread-safe, non-blocking read operation.
     *
     * @return Number of events in queue
     */
    fun size(): Int = lock.read {
        queue.size
    }

    /**
     * Check if the queue is empty.
     * Thread-safe, non-blocking read operation.
     *
     * @return true if queue is empty, false otherwise
     */
    fun isEmpty(): Boolean = lock.read {
        queue.isEmpty()
    }

    /**
     * Enqueue an event. If capacity is exceeded, drops the oldest event.
     * Thread-safe, blocking write operation.
     *
     * @param event The enriched event to enqueue
     */
    fun enqueue(event: EnrichedEventPayload) = lock.write {
        // Check if we need to drop the oldest event
        if (queue.size >= maxCapacity) {
            val droppedEvent = queue.removeFirstOrNull()
            if (droppedEvent != null) {
                Logger.warn("[MetaRouter] Queue cap $maxCapacity reached — dropped oldest event (messageId: ${droppedEvent.messageId})")
            }
        }

        queue.addLast(event)
    }

    /**
     * Drain events from the queue in batches.
     * Returns up to maxBatchSize events and removes them from the queue.
     * Thread-safe, blocking write operation.
     *
     * @param maxBatchSize Maximum number of events to drain
     * @return List of events (may be empty, never exceeds maxBatchSize)
     */
    fun drain(maxBatchSize: Int): List<EnrichedEventPayload> = lock.write {
        val batchSize = minOf(maxBatchSize, queue.size)
        if (batchSize == 0) {
            return emptyList()
        }

        // Remove from front (FIFO)
        val batch = mutableListOf<EnrichedEventPayload>()
        repeat(batchSize) {
            queue.removeFirstOrNull()?.let { batch.add(it) }
        }

        batch
    }

    /**
     * Peek at the next event without removing it.
     * Thread-safe, non-blocking read operation.
     *
     * @return The next event to be drained, or null if queue is empty
     */
    fun peek(): EnrichedEventPayload? = lock.read {
        queue.firstOrNull()
    }

    /**
     * Clear all events from the queue.
     * Thread-safe, blocking write operation.
     */
    fun clear() = lock.write {
        val size = queue.size
        queue.clear()
        if (size > 0) {
            Logger.log("Cleared $size events from queue")
        }
    }

    /**
     * Requeue events at the front of the queue (for retry scenarios).
     * Thread-safe, blocking write operation.
     *
     * Used when a batch fails and needs to be retried.
     * Events are added to the front to maintain send order.
     *
     * @param events Events to add back to the front of the queue
     */
    fun requeueAtFront(events: List<EnrichedEventPayload>) = lock.write {
        if (events.isEmpty()) return

        // Add to front in reverse order to maintain original order
        events.reversed().forEach { event ->
            // Check capacity before adding
            if (queue.size >= maxCapacity) {
                // Drop from the end (newest events) to make room
                queue.removeLastOrNull()?.let {
                    Logger.warn("[MetaRouter] Queue cap $maxCapacity reached during requeue — dropped newest event (messageId: ${it.messageId})")
                }
            }
            queue.addFirst(event)
        }

        Logger.log("Requeued ${events.size} events at front")
    }

    /**
     * Get debug information about the queue state.
     * Thread-safe, non-blocking read operation.
     *
     * @return Map with queue statistics
     */
    fun getDebugInfo(): Map<String, Any> = lock.read {
        mapOf(
            "size" to queue.size,
            "capacity" to maxCapacity,
            "isEmpty" to queue.isEmpty(),
            "utilizationPercent" to if (maxCapacity > 0) (queue.size * 100.0 / maxCapacity) else 0.0
        )
    }
}

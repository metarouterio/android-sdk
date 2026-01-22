package com.metarouter.analytics.queue

import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.utils.Logger

/**
 * Thread-safe FIFO queue for enriched events with overflow handling.
 */
class EventQueue(private val maxCapacity: Int = 2000) {

    private val queue = ArrayDeque<EnrichedEventPayload>()

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun enqueue(event: EnrichedEventPayload) {
        if (queue.size >= maxCapacity) {
            queue.removeFirstOrNull()?.let {
                Logger.warn("Queue capacity $maxCapacity reached - dropped oldest event (messageId: ${it.messageId})")
            }
        }
        queue.addLast(event)
    }

    @Synchronized
    fun clear() {
        val count = queue.size
        queue.clear()
        if (count > 0) {
            Logger.log("Cleared $count events from queue")
        }
    }

    /**
     * Remove and return up to [max] events from the front of the queue.
     * Used by Dispatcher to get events for batch transmission.
     *
     * @param max Maximum number of events to drain
     * @return List of events removed from the front
     */
    @Synchronized
    fun drain(max: Int): List<EnrichedEventPayload> {
        val n = minOf(max, queue.size)
        return (0 until n).map { queue.removeFirst() }
    }

    /**
     * Add events back to the front of the queue.
     * Used to requeue failed batches for retry.
     *
     * @param events Events to add to front (order preserved)
     */
    @Synchronized
    fun requeueToFront(events: List<EnrichedEventPayload>) {
        events.asReversed().forEach { queue.addFirst(it) }
    }
}

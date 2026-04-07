package com.metarouter.analytics.queue

import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.utils.Logger

/**
 * Thread-safe FIFO queue for enriched events with overflow handling.
 */
class EventQueue(private val maxCapacity: Int = 2000) : EventQueueInterface {

    private val queue = ArrayDeque<EnrichedEventPayload>()

    @Synchronized
    override fun size(): Int = queue.size

    @Synchronized
    override fun enqueue(event: EnrichedEventPayload) {
        if (queue.size >= maxCapacity) {
            queue.removeFirstOrNull()?.let {
                Logger.warn("Queue capacity $maxCapacity reached - dropped oldest event (messageId: ${it.messageId})")
            }
        }
        queue.addLast(event)
    }

    @Synchronized
    override fun clear() {
        val count = queue.size
        queue.clear()
        if (count > 0) {
            Logger.log("Cleared $count events from queue")
        }
    }

    @Synchronized
    override fun drain(max: Int): List<EnrichedEventPayload> {
        val n = minOf(max, queue.size)
        return (0 until n).map { queue.removeFirst() }
    }

    @Synchronized
    override fun requeueToFront(events: List<EnrichedEventPayload>) {
        events.asReversed().forEach { event ->
            if (queue.size >= maxCapacity) {
                queue.removeLastOrNull()?.let {
                    Logger.warn("Queue at capacity during requeue - dropped newest event (messageId: ${it.messageId})")
                }
            }
            queue.addFirst(event)
        }
    }
}

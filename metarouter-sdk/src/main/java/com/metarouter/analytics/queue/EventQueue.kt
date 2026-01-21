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
}

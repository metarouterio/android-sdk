package com.metarouter.analytics.queue

import com.metarouter.analytics.types.EnrichedEventPayload

/**
 * Interface for event queues used by the Dispatcher.
 * Implementations must be thread-safe.
 */
interface EventQueueInterface {
    fun size(): Int
    fun enqueue(event: EnrichedEventPayload)
    fun drain(max: Int): List<EnrichedEventPayload>
    fun requeueToFront(events: List<EnrichedEventPayload>)
    fun clear()
}

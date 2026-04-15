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

    /**
     * Flush all events to offline storage. Called by the dispatcher when
     * a flush is triggered but the device is offline.
     * Default implementation is a no-op (returns false).
     *
     * @return true if events were flushed to offline storage
     */
    fun flushToOfflineStorage(): Boolean = false
}

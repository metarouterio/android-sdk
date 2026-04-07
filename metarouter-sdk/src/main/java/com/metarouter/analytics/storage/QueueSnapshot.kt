package com.metarouter.analytics.storage

import com.metarouter.analytics.types.EnrichedEventPayload
import kotlinx.serialization.Serializable

/**
 * Versioned envelope for persisting event queue state to disk.
 *
 * The version field enables forward-compatible schema migration:
 * readers that encounter an unrecognized version skip and delete the file with a warning.
 *
 * Schema: { "version": 1, "events": [...] }
 *
 * @property version Schema version (currently 1)
 * @property events The event queue contents at flush time
 */
@Serializable
data class QueueSnapshot(
    val version: Int = 1,
    val events: List<EnrichedEventPayload>
)

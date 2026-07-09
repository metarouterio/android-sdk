package com.metarouter.analytics.webview

/**
 * Bounded record of recently seen bridge `messageId`s.
 *
 * Redelivery happens when the page re-posts after a lost ack, so a duplicate can only
 * arrive within a short window of the original — entries older than [ttlMillis] can be
 * treated as never-seen. The size bound exists because a long-lived webview session
 * would otherwise grow the store for its whole lifetime; when full, the oldest entry is
 * evicted first since it is also the one least likely to still be inside any redelivery
 * window.
 *
 * In-memory only: a duplicate delivered across a process restart is indistinguishable
 * from a fresh event and rare enough not to justify disk I/O on the message path.
 */
internal class BridgeDedupStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    init {
        require(maxEntries > 0) { "maxEntries must be > 0" }
        require(ttlMillis > 0) { "ttlMillis must be > 0" }
    }

    // Insertion-ordered so removeEldestEntry evicts the oldest messageId. Guarded by
    // synchronized(this): messages arrive on the WebView's thread while the SDK may
    // probe from coroutine workers.
    private val seen = object : LinkedHashMap<String, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Long>): Boolean {
            return size > maxEntries
        }
    }

    /**
     * Records [messageId] and reports whether it was new.
     *
     * @return `true` if unseen (or seen only outside the TTL window) — caller should
     *   process the message; `false` for a live duplicate — caller should drop it.
     *   A duplicate does NOT refresh the original timestamp: refreshing would let a
     *   producer stuck in a re-post loop keep its entry alive forever.
     */
    fun markIfNew(messageId: String): Boolean {
        val now = clock()
        synchronized(this) {
            val firstSeenAt = seen[messageId]
            if (firstSeenAt != null && now - firstSeenAt < ttlMillis) {
                return false
            }
            // Remove before re-put so an expired entry moves to the back of the
            // eviction order instead of keeping its stale position.
            seen.remove(messageId)
            seen[messageId] = now
            return true
        }
    }

    fun size(): Int = synchronized(this) { seen.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 1_000
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
    }
}

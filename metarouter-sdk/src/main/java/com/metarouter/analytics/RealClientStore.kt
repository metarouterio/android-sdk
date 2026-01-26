package com.metarouter.analytics

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe singleton storage for the MetaRouterAnalyticsClient.
 */
internal class RealClientStore {
    private val mutex = Mutex()

    @Volatile
    private var client: MetaRouterAnalyticsClient? = null

    /**
     * Store a client instance.
     * @return true if the client was stored, false if a client was already stored
     */
    suspend fun set(newClient: MetaRouterAnalyticsClient): Boolean {
        mutex.withLock {
            if (client != null) {
                return false
            }
            client = newClient
            return true
        }
    }

    /**
     * Get the stored client, if any.
     */
    suspend fun get(): MetaRouterAnalyticsClient? {
        mutex.withLock {
            return client
        }
    }

    /**
     * Clear the stored client, allowing a new one to be set.
     */
    suspend fun clear() {
        mutex.withLock {
            client = null
        }
    }

    /**
     * Quick non-suspend check if a client is stored.
     * This is a snapshot that may be stale immediately after return.
     */
    fun hasClient(): Boolean = client != null
}

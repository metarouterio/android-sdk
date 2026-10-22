package com.metarouter.analytics.consent

import java.io.Closeable

/**
 * Returned by `subscribeToConsent`: keep it for as long as the handler should run.
 *
 * Not closed on garbage collection. Consent changes are compliance signals, so a
 * dropped reference must not silently stop them; a discarded subscription delivers for
 * the life of the process instead. Implements [Closeable], so `use { }` and any
 * lifecycle helper that closes resources work with it.
 */
class ConsentSubscription internal constructor(onClose: () -> Unit) : Closeable {
    private val lock = Any()
    private var onClose: (() -> Unit)? = onClose

    /** Whether [close] has been called. */
    val isClosed: Boolean
        get() = synchronized(lock) { onClose == null }

    /**
     * Stops delivery. Idempotent. Called on the main thread, the handler is not invoked
     * again, even for a delivery that was already queued.
     */
    override fun close() {
        val pending = synchronized(lock) {
            val current = onClose
            onClose = null
            current
        }
        pending?.invoke()
    }
}

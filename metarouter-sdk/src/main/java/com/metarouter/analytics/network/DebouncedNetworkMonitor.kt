package com.metarouter.analytics.network

import com.metarouter.analytics.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decorator that wraps an inner [NetworkMonitor] and debounces online transitions.
 *
 * - **Offline transitions** propagate immediately (no delay in pausing HTTP attempts).
 * - **Online transitions** are debounced — the callback only fires after connectivity
 *   has been stable for [DEBOUNCE_MS] milliseconds.
 * - Rapid flapping (online/offline/online) collapses into a single online action
 *   once connectivity stabilizes.
 *
 * Consumers use this exactly like any [NetworkMonitor] — the debounce is transparent.
 */
internal class DebouncedNetworkMonitor(
    private val inner: NetworkMonitor,
    private val scope: CoroutineScope
) : NetworkMonitor {

    @Volatile
    override var isConnected: Boolean = inner.isConnected
        private set

    @Volatile
    private var debounceJob: Job? = null

    override fun start(onConnectivityChanged: (isConnected: Boolean) -> Unit) {
        inner.start { connected ->
            debounceJob?.cancel()
            debounceJob = null

            if (connected) {
                // Online: debounce — only propagate after connectivity is stable for 2s
                Logger.log("Network connectivity detected — debouncing for stability")
                debounceJob = scope.launch {
                    delay(DEBOUNCE_MS)
                    Logger.log("Network connectivity stable — resuming dispatcher")
                    isConnected = true
                    onConnectivityChanged(true)
                }
            } else {
                // Offline: propagate immediately
                Logger.log("Network connectivity lost — pausing HTTP delivery")
                isConnected = false
                onConnectivityChanged(false)
            }
        }
    }

    override fun stop() {
        debounceJob?.cancel()
        debounceJob = null
        inner.stop()
    }

    companion object {
        internal const val DEBOUNCE_MS = 2000L
    }
}

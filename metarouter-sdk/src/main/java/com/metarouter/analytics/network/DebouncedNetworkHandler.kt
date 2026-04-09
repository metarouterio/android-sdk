package com.metarouter.analytics.network

import com.metarouter.analytics.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces network connectivity transitions before propagating to the dispatcher.
 *
 * - **Offline transitions** fire immediately (no delay in pausing HTTP attempts).
 * - **Online transitions** are debounced — the [onOnline] callback only fires after
 *   connectivity has been stable for [DEBOUNCE_MS] milliseconds.
 * - Rapid flapping (online/offline/online) collapses into a single online action
 *   once connectivity stabilizes.
 */
internal class DebouncedNetworkHandler(
    private val scope: CoroutineScope,
    private val onOnline: suspend () -> Unit,
    private val onOffline: () -> Unit
) {
    @Volatile
    private var debounceJob: Job? = null

    fun onConnectivityChanged(connected: Boolean) {
        if (connected) {
            // Online: debounce — only act after connectivity is stable for 2s
            debounceJob?.cancel()
            Logger.log("Network connectivity detected — debouncing for stability")
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                Logger.log("Network connectivity stable — resuming dispatcher")
                onOnline()
            }
        } else {
            // Offline: act immediately, cancel any pending online debounce
            debounceJob?.cancel()
            debounceJob = null
            Logger.log("Network connectivity lost — pausing HTTP delivery")
            onOffline()
        }
    }

    /**
     * Cancel any pending debounce timer. Call during reset/teardown.
     */
    fun cancel() {
        debounceJob?.cancel()
        debounceJob = null
    }

    companion object {
        /** Debounce interval for online transitions. Hardcoded per spec. */
        internal const val DEBOUNCE_MS = 2000L
    }
}

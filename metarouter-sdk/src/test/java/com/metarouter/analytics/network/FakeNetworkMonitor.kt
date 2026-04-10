package com.metarouter.analytics.network

/**
 * Test double for [NetworkMonitor] that allows simulating connectivity changes.
 */
class FakeNetworkMonitor(initialConnected: Boolean = true) : NetworkMonitor {
    @Volatile
    override var isConnected: Boolean = initialConnected
        private set

    private var listener: ((Boolean) -> Unit)? = null

    override fun start(onConnectivityChanged: (Boolean) -> Unit) {
        listener = onConnectivityChanged
    }

    override fun stop() {
        listener = null
    }

    /** Simulate connectivity change from test code. */
    fun setConnected(connected: Boolean) {
        if (isConnected != connected) {
            isConnected = connected
            listener?.invoke(connected)
        }
    }
}

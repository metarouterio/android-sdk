package com.metarouter.analytics.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.metarouter.analytics.utils.Logger

/**
 * Provides a reactive connectivity signal for the SDK.
 * Implementations wrap platform-specific network monitoring APIs.
 */
interface NetworkMonitor {
    val isConnected: Boolean
    fun start(onConnectivityChanged: (isConnected: Boolean) -> Unit)
    fun stop()
}

/**
 * Android implementation wrapping [ConnectivityManager.NetworkCallback].
 *
 * Gracefully degrades to "always connected" if:
 * - ConnectivityManager is unavailable
 * - ACCESS_NETWORK_STATE permission is denied
 *
 * Thread safety: [isConnected] is @Volatile for safe reads from any thread.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    @Volatile
    override var isConnected: Boolean = true
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    private var listener: ((Boolean) -> Unit)? = null

    init {
        connectivityManager = try {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (e: Exception) {
            null // graceful fallback: always connected
        }

        // Snapshot initial state
        connectivityManager?.let { cm ->
            isConnected = try {
                val network = cm.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } catch (e: Exception) {
                true // fallback
            }
        }
    }

    override fun start(onConnectivityChanged: (isConnected: Boolean) -> Unit) {
        listener = onConnectivityChanged
        val cm = connectivityManager ?: return // no CM = always connected, no callbacks

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isConnected) {
                    isConnected = true
                    listener?.invoke(true)
                }
            }

            override fun onLost(network: Network) {
                // Only mark disconnected if there's truly no active network
                val activeNet = cm.activeNetwork
                val caps = activeNet?.let { cm.getNetworkCapabilities(it) }
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                if (!hasInternet && isConnected) {
                    isConnected = false
                    listener?.invoke(false)
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, cb)
            callback = cb
        } catch (e: SecurityException) {
            Logger.log("NetworkMonitor: missing permission, falling back to always-connected")
        }
    }

    override fun stop() {
        callback?.let { cb ->
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (_: Exception) { }
        }
        callback = null
        listener = null
    }
}

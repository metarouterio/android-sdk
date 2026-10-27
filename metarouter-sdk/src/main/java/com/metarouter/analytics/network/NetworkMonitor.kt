package com.metarouter.analytics.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.metarouter.analytics.utils.Logger

/**
 * Provides a reactive connectivity signal for the SDK.
 * Implementations wrap platform-specific network monitoring APIs.
 */
interface NetworkMonitor {
    val isConnected: Boolean
    fun start(onConnectivityChanged: (isConnected: Boolean) -> Unit)
    fun stop()

    /**
     * Whether the default network is wifi; `null` when unknown. Read per event by
     * enrichment for `context.network.wifi`. Implementations that predate interface
     * tracking report unknown, which leaves `context.network` out rather than
     * failing the host's build.
     */
    val isWifi: Boolean? get() = null
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

    /**
     * Seeded synchronously at construction, so the first event already has it — unlike
     * iOS, whose path monitor only answers asynchronously. `false` with no active
     * network (iOS reports an unsatisfied path the same way); `null` only when the
     * ConnectivityManager is unavailable or the read is refused.
     */
    @Volatile
    override var isWifi: Boolean? = null
        private set

    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var stopped = false
    /** Guards [stopped] + [isWifi] together, so a callback cannot write after stop(). */
    private val wifiLock = Any()
    private var connectivityManager: ConnectivityManager? = null
    @Volatile private var listener: ((Boolean) -> Unit)? = null

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
            isWifi = readActiveIsWifi(cm)
        }
    }

    private fun readActiveIsWifi(cm: ConnectivityManager): Boolean? = try {
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
    } catch (e: Exception) {
        null
    }

    private fun setIsWifi(value: Boolean?) {
        // Check and write under one lock: a callback already in flight when stop()
        // ran would otherwise restore the value stop() cleared, and every later event
        // would stamp it — with callbacks unregistered, nothing would correct it.
        synchronized(wifiLock) {
            if (!stopped) isWifi = value
        }
    }

    /**
     * Tracks the interface of the DEFAULT network — the one the app's traffic uses.
     * The connectivity callback below hears every network with INTERNET, so with wifi
     * and cellular both up it cannot say which one is in use. API 24+ has a
     * default-network callback; on 23, or when registering it fails, the connectivity
     * callback re-reads `activeNetwork` instead. Returns whether the default-network
     * callback is in place.
     */
    private fun startWifiTracking(cm: ConnectivityManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        unregister(wifiCallback)
        wifiCallback = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            // onCapabilitiesChanged only follows onAvailable reliably from API 26, so
            // on 24-25 a switch can arrive as onAvailable alone.
            override fun onAvailable(network: Network) {
                setIsWifi(readIsWifi(cm, network))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                setIsWifi(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            }

            override fun onLost(network: Network) {
                setIsWifi(false)
            }
        }
        return try {
            cm.registerDefaultNetworkCallback(cb)
            wifiCallback = cb
            true
        } catch (e: Exception) {
            // Falls back to re-reading rather than freezing the initial value, which
            // would be the staleness this tracking exists to remove.
            Logger.log("NetworkMonitor: default-network callback unavailable, re-reading the active network instead")
            false
        }
    }

    private fun readIsWifi(cm: ConnectivityManager, network: Network): Boolean? = try {
        cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
    } catch (e: Exception) {
        null
    }

    private fun unregister(cb: ConnectivityManager.NetworkCallback?) {
        cb ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(cb)
        } catch (_: Exception) { }
    }

    override fun start(onConnectivityChanged: (isConnected: Boolean) -> Unit) {
        listener = onConnectivityChanged
        val cm = connectivityManager ?: return // no CM = always connected, no callbacks
        synchronized(wifiLock) { stopped = false }
        // stop() cleared the value; a restart must not wait for the next switch to know it.
        setIsWifi(readActiveIsWifi(cm))
        val reReadWifi = !startWifiTracking(cm)
        // A second start() without stop() would otherwise leak the first registration.
        unregister(callback)
        callback = null

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (reReadWifi) setIsWifi(readActiveIsWifi(cm))
                if (!isConnected) {
                    isConnected = true
                    listener?.invoke(true)
                }
            }

            override fun onLost(network: Network) {
                // activeNetwork may not be cleared yet when this fires, so we
                // can't rely on re-querying capabilities. Instead, treat the
                // loss as offline unless a *different* network is still active
                // (handoff case, e.g. WiFi → cellular).
                val activeNet = try {
                    cm.activeNetwork
                } catch (e: SecurityException) {
                    null
                }
                val stillOnline = activeNet != null && activeNet != network
                if (reReadWifi) {
                    setIsWifi(
                        if (stillOnline) {
                            try {
                                cm.getNetworkCapabilities(activeNet)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
                            } catch (e: Exception) { null }
                        } else false
                    )
                }
                if (!stillOnline && isConnected) {
                    isConnected = false
                    listener?.invoke(false)
                }
            }

            // Re-read path only: a joining wifi fires onAvailable before it is validated
            // and made the default, so activeNetwork still reads cellular there. Its
            // validation arrives as a capabilities change, which is when it takes over.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (reReadWifi) setIsWifi(readActiveIsWifi(cm))
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
        // Unknown, not last-known: a stopped monitor sees no further switches, and
        // stamping a frozen value is the staleness per-event reads exist to remove.
        synchronized(wifiLock) {
            stopped = true
            isWifi = null
        }
        unregister(callback)
        unregister(wifiCallback)
        callback = null
        wifiCallback = null
        listener = null
    }
}

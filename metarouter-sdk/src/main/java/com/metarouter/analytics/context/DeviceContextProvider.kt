package com.metarouter.analytics.context

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.metarouter.analytics.BuildConfig
import com.metarouter.analytics.types.*
import com.metarouter.analytics.utils.Logger
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Provides device, app, OS, screen, network, locale, and timezone context information.
 *
 * - Context is calculated once and cached for app lifetime
 * - Cache is invalidated when advertising ID changes (set/cleared)
 * - Cache includes advertising ID value to detect changes
 *
 * Android Considerations:
 * - Requires Application context (not Activity context) to avoid memory leaks
 * - Network WiFi detection requires ACCESS_NETWORK_STATE permission (graceful degradation if missing)
 * - Screen dimensions use DisplayMetrics (no configuration changes handled - cache is static)
 *
 * Thread Safety:
 * - Uses AtomicReference for lock-free reads after first generation
 * - Cache generation is synchronized to prevent duplicate work
 * - Safe for concurrent access from multiple threads
 */
class DeviceContextProvider(private val context: Context) {

    // Sentinel value to indicate "not cached yet"
    private object NotCached

    // Cached context with advertising ID value as cache key
    private val cachedContext = AtomicReference<Any>(NotCached)
    private val cachedAdvertisingId = AtomicReference<String?>(null)

    private val cacheLock = Any()

    companion object {
        private const val SDK_NAME = "metarouter-android-sdk"
        private val SDK_VERSION = BuildConfig.SDK_VERSION
        private const val UNKNOWN = "unknown"
    }

    /**
     * Get complete event context with all metadata.
     * Returns cached context if available and advertising ID hasn't changed.
     *
     * @param advertisingId Optional advertising ID (GAID) to include in device context
     * @return Complete EventContext with all metadata
     */
    fun getContext(advertisingId: String? = null): EventContext {
        // Check if we have a valid cache for this advertising ID
        val cached = cachedContext.get()
        if (cached !== NotCached && cachedAdvertisingId.get() == advertisingId) {
            Logger.log("Returning cached context")
            return cached as EventContext
        }

        // Cache miss or advertising ID changed - regenerate context
        return synchronized(cacheLock) {
            // Double-check after acquiring lock
            val recheck = cachedContext.get()
            if (recheck !== NotCached && cachedAdvertisingId.get() == advertisingId) {
                return recheck as EventContext
            }

            Logger.log("Generating fresh context (advertisingId=${if (advertisingId != null) "present" else "null"})")
            val newContext = generateContext(advertisingId)

            // Update cache
            cachedContext.set(newContext)
            cachedAdvertisingId.set(advertisingId)

            newContext
        }
    }

    /**
     * Clear the context cache. Called when advertising ID changes.
     * Per spec: "when advertising ID is set or cleared, cache is cleared and regenerated"
     */
    fun clearCache() {
        synchronized(cacheLock) {
            cachedContext.set(NotCached)
            cachedAdvertisingId.set(null)
            Logger.log("Context cache cleared")
        }
    }

    /**
     * Generate fresh context information by collecting all metadata.
     */
    private fun generateContext(advertisingId: String?): EventContext {
        return EventContext(
            library = getLibraryContext(),
            locale = getLocale(),
            timezone = getTimezone(),
            device = getDeviceContext(advertisingId),
            os = getOSContext(),
            app = getAppContext(),
            screen = getScreenContext(),
            network = getNetworkContext()
        )
    }

    /**
     * Get SDK library metadata.
     */
    private fun getLibraryContext(): LibraryContext {
        return LibraryContext(
            name = SDK_NAME,
            version = SDK_VERSION
        )
    }

    /**
     * Get device locale from system settings.
     * Fallback: "en-US"
     */
    private fun getLocale(): String {
        return try {
            val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.resources.configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                context.resources.configuration.locale
            }
            locale.toString().replace('_', '-')
        } catch (e: Exception) {
            Logger.warn("Failed to get locale: ${e.message}")
            "en-US"
        }
    }

    /**
     * Get device timezone.
     * Fallback: "UTC"
     */
    private fun getTimezone(): String {
        return try {
            TimeZone.getDefault().id
        } catch (e: Exception) {
            Logger.warn("Failed to get timezone: ${e.message}")
            "UTC"
        }
    }

    /**
     * Get device information including manufacturer, model, name, type, and optional advertising ID.
     */
    private fun getDeviceContext(advertisingId: String?): DeviceContext {
        return DeviceContext(
            manufacturer = Build.MANUFACTURER.takeIf { it.isNotBlank() } ?: UNKNOWN,
            model = Build.MODEL.takeIf { it.isNotBlank() } ?: UNKNOWN,
            name = Build.DEVICE.takeIf { it.isNotBlank() } ?: UNKNOWN,
            type = "android",
            advertisingId = advertisingId
        )
    }

    /**
     * Get operating system information (Android version).
     */
    private fun getOSContext(): OSContext {
        return OSContext(
            name = "Android",
            version = Build.VERSION.RELEASE.takeIf { it.isNotBlank() } ?: UNKNOWN
        )
    }

    /**
     * Get app information from PackageManager.
     * Collects app name, version, build number, and namespace (package name).
     */
    private fun getAppContext(): AppContext {
        return try {
            val packageManager = context.packageManager
            val packageName = context.packageName
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }

            val appName = try {
                packageInfo.applicationInfo?.let {
                    packageManager.getApplicationLabel(it).toString()
                } ?: UNKNOWN
            } catch (e: Exception) {
                UNKNOWN
            }

            val versionName = packageInfo.versionName ?: UNKNOWN
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }

            AppContext(
                name = appName,
                version = versionName,
                build = versionCode,
                namespace = packageName
            )
        } catch (e: Exception) {
            Logger.warn("Failed to get app context: ${e.message}")
            AppContext(
                name = UNKNOWN,
                version = UNKNOWN,
                build = UNKNOWN,
                namespace = context.packageName
            )
        }
    }

    /**
     * Get screen dimensions and pixel density from WindowManager.
     * Width/height are in points (dp), density is pixel ratio rounded to 2 decimal places.
     */
    private fun getScreenContext(): ScreenContext {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.getRealMetrics(displayMetrics)
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            }

            // Convert pixels to dp for width/height
            val widthDp = (displayMetrics.widthPixels / displayMetrics.density).roundToInt()
            val heightDp = (displayMetrics.heightPixels / displayMetrics.density).roundToInt()

            // Round density to 2 decimal places
            val density = (displayMetrics.density * 100).roundToInt() / 100.0

            ScreenContext(
                width = widthDp,
                height = heightDp,
                density = density
            )
        } catch (e: Exception) {
            Logger.warn("Failed to get screen context: ${e.message}")
            ScreenContext(
                width = 0,
                height = 0,
                density = 1.0
            )
        }
    }

    /**
     * Get network connectivity information (WiFi detection).
     * Requires ACCESS_NETWORK_STATE permission - gracefully returns null if missing.
     * Android only feature per spec.
     */
    private fun getNetworkContext(): NetworkContext {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val isWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                @Suppress("DEPRECATION")
                networkInfo?.type == ConnectivityManager.TYPE_WIFI
            }

            NetworkContext(wifi = isWifi)
        } catch (e: SecurityException) {
            // Permission not granted - graceful degradation
            Logger.log("ACCESS_NETWORK_STATE permission not granted, network context unavailable")
            NetworkContext(wifi = null)
        } catch (e: Exception) {
            Logger.warn("Failed to get network context: ${e.message}")
            NetworkContext(wifi = null)
        }
    }
}

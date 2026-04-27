package com.metarouter.analytics.types

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.metarouter.analytics.utils.Logger
import kotlinx.serialization.Serializable

/**
 * Complete context information for an event, including app, device, OS, screen, network,
 * library, locale, and timezone data.
 */
@Serializable
data class EventContext(
    val app: AppContext? = null,
    val device: DeviceContext? = null,
    val library: LibraryContext,
    val locale: String? = null,
    val network: NetworkContext? = null,
    val os: OSContext? = null,
    val screen: ScreenContext? = null,
    val timezone: String? = null
)

/**
 * Application metadata including name, version, build number, and namespace (package name).
 */
@Serializable
data class AppContext(
    val name: String,
    val version: String,
    val build: String,
    val namespace: String
) {
    companion object {
        private const val UNKNOWN = "unknown"

        /**
         * Read app metadata from `PackageManager` once. Safe to cache for the
         * process lifetime — the manifest values are immutable post-install.
         *
         * Both the lifecycle subsystem and `DeviceContextProvider` consume this
         * cached snapshot so per-event enrichment does not re-read the manifest.
         */
        fun fromContext(context: Context): AppContext {
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
                Logger.warn("Failed to read AppContext from PackageManager: ${e.message}")
                // Derive a best-effort `name` from the package's last segment when the
                // PackageManager read fails entirely. Better than literal "unknown" on
                // ingest dashboards and matches what most launchers show when the
                // applicationLabel resolution itself fails.
                val packageName = context.packageName
                val derivedName = packageName.substringAfterLast('.')
                    .takeIf { it.isNotBlank() }
                    ?: UNKNOWN
                AppContext(
                    name = derivedName,
                    version = UNKNOWN,
                    build = UNKNOWN,
                    namespace = packageName
                )
            }
        }
    }
}

/**
 * Device information including manufacturer, model, device name, and type.
 */
@Serializable
data class DeviceContext(
    val manufacturer: String,
    val model: String,
    val name: String,
    val type: String,
    val advertisingId: String? = null
)

/**
 * SDK library metadata.
 */
@Serializable
data class LibraryContext(
    val name: String,
    val version: String
)

/**
 * Operating system information.
 */
@Serializable
data class OSContext(
    val name: String,
    val version: String
)

/**
 * Screen dimensions and pixel density.
 */
@Serializable
data class ScreenContext(
    val width: Int,
    val height: Int,
    val density: Double
)

/**
 * Network connectivity information.
 * Currently tracks WiFi status (Android only).
 */
@Serializable
data class NetworkContext(
    val wifi: Boolean? = null
)

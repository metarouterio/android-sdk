package com.metarouter.analytics.types

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
)

/**
 * Device information including manufacturer, model, device name, and type.
 */
@Serializable
data class DeviceContext(
    val manufacturer: String,
    val model: String,
    val name: String,
    val type: String
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

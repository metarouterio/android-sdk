package com.metarouter.analytics.lifecycle

/**
 * Centralized event-name constants for the four application-lifecycle events.
 * Single source of truth shared by the tracker (emission) and any future
 * coordinator / observer wiring.
 */
internal object LifecycleEventNames {
    const val INSTALLED = "Application Installed"
    const val UPDATED = "Application Updated"
    const val OPENED = "Application Opened"
    const val BACKGROUNDED = "Application Backgrounded"
}

/**
 * Centralized property-name constants for lifecycle event payloads.
 */
internal object LifecycleEventProperties {
    const val VERSION = "version"
    const val BUILD = "build"
    const val PREVIOUS_VERSION = "previous_version"
    const val PREVIOUS_BUILD = "previous_build"
    const val FROM_BACKGROUND = "from_background"
    const val URL = "url"
    const val REFERRING_APPLICATION = "referring_application"
}

internal const val LIFECYCLE_UNKNOWN = "unknown"

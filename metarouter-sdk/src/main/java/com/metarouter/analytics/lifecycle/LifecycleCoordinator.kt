package com.metarouter.analytics.lifecycle

import android.net.Uri

/**
 * Bridge between the platform notification observer ([AppLifecycleObserver]) and the
 * lifecycle event tracker. Single seam where future session/attribution work will land
 * alongside lifecycle events without bloating [MetaRouterAnalyticsClient].
 *
 * The coordinator is constructed only when `InitOptions.trackLifecycleEvents` is `true`;
 * when the feature is disabled, the client never holds an instance and all coordinator
 * calls are null-safe no-ops at the call sites.
 */
internal class LifecycleCoordinator(
    private val tracker: LifecycleEventTracker
) {
    /** Called from `AppLifecycleObserver.onStart` (`ProcessLifecycleOwner.ON_START`). */
    fun onForeground() = tracker.onForeground()

    /** Called from `AppLifecycleObserver.onStop` (`ProcessLifecycleOwner.ON_STOP`). */
    fun onBackground() = tracker.onBackground()

    /**
     * Called once after the SDK transitions to `READY`. Runs the cold-launch
     * detection + emission sequence (install/update + initial Opened).
     */
    fun onReady() = tracker.onSdkReady()

    /** Buffer a deep link for the next `Application Opened` emission. */
    fun openURL(uri: Uri, sourceApplication: String?) = tracker.openURL(uri, sourceApplication)
}

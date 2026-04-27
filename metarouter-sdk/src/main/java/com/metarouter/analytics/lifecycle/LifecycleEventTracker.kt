package com.metarouter.analytics.lifecycle

import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.metarouter.analytics.AnalyticsInterface
import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.storage.LifecycleStorage
import com.metarouter.analytics.utils.Logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns install/update detection and emission of the four application-lifecycle events:
 * `Application Installed`, `Application Updated`, `Application Opened`, and
 * `Application Backgrounded`.
 *
 * Wiring:
 * - [onSdkReady] is called once after the client transitions to READY. It runs the cold-launch
 *   sequence (install/update detection → `Application Opened {from_background:false}`).
 * - [onForeground] / [onBackground] are called from the existing `AppLifecycleObserver` hooks
 *   for `background → active` and `active → background` transitions.
 * - [handleDeepLink] buffers deep-link properties for inclusion on the next `Application Opened`.
 *
 * When [enabled] is `false`, every entry point is a no-op.
 */
internal class LifecycleEventTracker(
    private val analytics: AnalyticsInterface,
    private val storage: LifecycleStorage,
    private val contextProvider: DeviceContextProvider,
    private val identityManager: IdentityManager,
    private val enabled: Boolean,
    private val foregroundStateProvider: () -> Boolean = { defaultForegroundCheck() }
) {

    /**
     * Set to `true` when the cold-launch path emits `Application Opened`. Causes the
     * imminent (first) `onForeground` callback — which `ProcessLifecycleOwner.onStart`
     * will fire right after registration — to be suppressed so we don't double-emit.
     */
    private val suppressNextForeground = AtomicBoolean(false)

    /**
     * Set to `true` when the cold-launch path is suppressed because the process started
     * in the background (push, JobScheduler, etc.). The first true `onForeground` call
     * consumes this and emits the cold-launch-style `Application Opened {from_background:false}`.
     */
    private val coldLaunchOpenedDeferred = AtomicBoolean(false)

    private val pendingDeepLink = AtomicReference<DeepLink?>(null)

    /**
     * Run the cold-launch detection + emission sequence. Idempotent — only the first call
     * after construction has effect.
     *
     * 1. Detect install vs update vs no-op based on persisted (version, build) and identity.
     * 2. Persist current (version, build).
     * 3. Emit `Application Opened {from_background:false}` if the process is in foreground;
     *    otherwise defer it to the next foreground transition.
     */
    fun onSdkReady() {
        if (!enabled) return

        val app = contextProvider.getContext().app
        if (app == null) {
            Logger.warn("LifecycleEventTracker: app context unavailable, skipping cold-launch sequence")
            return
        }

        val currentVersion = app.version
        val currentBuild = app.build

        val storedVersion = storage.getVersion()
        val storedBuild = storage.getBuild()

        when {
            storedVersion == null && storedBuild == null -> {
                if (identityManager.hasAnyValue()) {
                    // Existing user upgrading from a pre-lifecycle SDK build.
                    emitUpdated(currentVersion, currentBuild, UNKNOWN, UNKNOWN)
                } else {
                    emitInstalled(currentVersion, currentBuild)
                }
            }
            storedVersion != currentVersion || storedBuild != currentBuild -> {
                emitUpdated(
                    currentVersion,
                    currentBuild,
                    storedVersion ?: UNKNOWN,
                    storedBuild ?: UNKNOWN
                )
            }
            else -> {
                // Same version/build — no install/update event.
            }
        }

        storage.setVersionBuild(currentVersion, currentBuild)

        if (foregroundStateProvider()) {
            emitOpened(fromBackground = false, version = currentVersion, build = currentBuild)
            // The first observer-driven onForeground call (firing right after observer
            // registration) must not double-emit.
            suppressNextForeground.set(true)
        } else {
            // Background-launched process (push, JobScheduler, WorkManager, etc.).
            // Defer the cold-launch Opened until the first true foreground entry.
            coldLaunchOpenedDeferred.set(true)
        }
    }

    /**
     * Called by the AppLifecycleObserver on `ProcessLifecycleOwner.onStart`.
     * Emits `Application Opened` with appropriate `from_background` value, with
     * dedup logic to avoid double-emit alongside the cold-launch path.
     */
    fun onForeground() {
        if (!enabled) return

        val app = contextProvider.getContext().app
        val version = app?.version ?: UNKNOWN
        val build = app?.build ?: UNKNOWN

        when {
            coldLaunchOpenedDeferred.compareAndSet(true, false) -> {
                // Cold launch was suppressed because the process started in background.
                // First true foreground entry emits the cold-launch-style Opened.
                emitOpened(fromBackground = false, version = version, build = build)
            }
            suppressNextForeground.compareAndSet(true, false) -> {
                // Cold-launch path already emitted Opened — suppress this duplicate.
            }
            else -> {
                emitOpened(fromBackground = true, version = version, build = build)
            }
        }
    }

    /**
     * Called by the AppLifecycleObserver on `ProcessLifecycleOwner.onStop`.
     */
    fun onBackground() {
        if (!enabled) return
        analytics.track(EVENT_BACKGROUNDED, emptyMap())
    }

    /**
     * Buffer deep-link properties for the next `Application Opened` event.
     * The buffer is one-shot — cleared after emission.
     *
     * Hosts should call this from the receiving Activity's `onCreate` / `onNewIntent`
     * (typically reading `intent.data` and `intent.getStringExtra(Intent.EXTRA_REFERRER)`).
     */
    fun handleDeepLink(uri: Uri, sourceApplication: String?) {
        if (!enabled) return
        pendingDeepLink.set(DeepLink(uri.toString(), sourceApplication))
    }

    private fun emitInstalled(version: String, build: String) {
        analytics.track(
            EVENT_INSTALLED,
            mapOf(
                PROP_VERSION to version,
                PROP_BUILD to build
            )
        )
    }

    private fun emitUpdated(
        version: String,
        build: String,
        previousVersion: String,
        previousBuild: String
    ) {
        analytics.track(
            EVENT_UPDATED,
            mapOf(
                PROP_VERSION to version,
                PROP_BUILD to build,
                PROP_PREVIOUS_VERSION to previousVersion,
                PROP_PREVIOUS_BUILD to previousBuild
            )
        )
    }

    private fun emitOpened(fromBackground: Boolean, version: String, build: String) {
        val props = mutableMapOf<String, Any?>(
            PROP_VERSION to version,
            PROP_BUILD to build,
            PROP_FROM_BACKGROUND to fromBackground
        )
        pendingDeepLink.getAndSet(null)?.let { deepLink ->
            props[PROP_URL] = deepLink.url
            deepLink.referringApplication?.let { props[PROP_REFERRING_APPLICATION] = it }
        }
        analytics.track(EVENT_OPENED, props)
    }

    private data class DeepLink(val url: String, val referringApplication: String?)

    companion object {
        const val EVENT_INSTALLED = "Application Installed"
        const val EVENT_UPDATED = "Application Updated"
        const val EVENT_OPENED = "Application Opened"
        const val EVENT_BACKGROUNDED = "Application Backgrounded"

        const val PROP_VERSION = "version"
        const val PROP_BUILD = "build"
        const val PROP_PREVIOUS_VERSION = "previous_version"
        const val PROP_PREVIOUS_BUILD = "previous_build"
        const val PROP_FROM_BACKGROUND = "from_background"
        const val PROP_URL = "url"
        const val PROP_REFERRING_APPLICATION = "referring_application"

        private const val UNKNOWN = "unknown"

        private fun defaultForegroundCheck(): Boolean {
            return try {
                ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
            } catch (e: Throwable) {
                // ProcessLifecycleOwner may not be available in some test contexts.
                // Default to false (defer Opened) to avoid spurious double-emits.
                false
            }
        }
    }
}

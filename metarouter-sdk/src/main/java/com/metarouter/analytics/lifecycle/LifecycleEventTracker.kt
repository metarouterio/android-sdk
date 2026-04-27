package com.metarouter.analytics.lifecycle

import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.metarouter.analytics.AnalyticsInterface
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.storage.LifecycleStorage
import com.metarouter.analytics.types.AppContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns install/update detection and emission of the four application-lifecycle
 * events: `Application Installed`, `Application Updated`, `Application Opened`,
 * and `Application Backgrounded`.
 *
 * The tracker has no on/off switch — when the feature is disabled, the host
 * (currently `LifecycleCoordinator`) never constructs an instance.
 */
internal class LifecycleEventTracker(
    private val analytics: AnalyticsInterface,
    private val storage: LifecycleStorage,
    private val appContext: AppContext,
    private val identityManager: IdentityManager,
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
     * Idempotency guard for [onSdkReady] — re-running the cold-launch sequence on a
     * second call would re-emit Installed/Updated/Opened and stomp `suppressNextForeground`,
     * eating the next legitimate foreground transition.
     */
    private val coldLaunchRan = AtomicBoolean(false)

    /**
     * Run the cold-launch detection + emission sequence. Idempotent — only the first call
     * runs the sequence; subsequent calls are no-ops.
     *
     * 1. Detect install vs update vs no-op based on persisted (version, build) and identity.
     * 2. Persist current (version, build).
     * 3. Emit `Application Opened {from_background:false}` if the process is in foreground;
     *    otherwise defer it to the next foreground transition.
     */
    fun onSdkReady() {
        if (!coldLaunchRan.compareAndSet(false, true)) return

        val currentVersion = appContext.version
        val currentBuild = appContext.build

        val storedVersion = storage.getVersion()
        val storedBuild = storage.getBuild()

        when {
            storedVersion == null && storedBuild == null -> {
                if (identityManager.hasAnyValue()) {
                    // Existing user upgrading from a pre-lifecycle SDK build.
                    emitUpdated(currentVersion, currentBuild, LIFECYCLE_UNKNOWN, LIFECYCLE_UNKNOWN)
                } else {
                    emitInstalled(currentVersion, currentBuild)
                }
            }
            storedVersion != currentVersion || storedBuild != currentBuild -> {
                emitUpdated(
                    currentVersion,
                    currentBuild,
                    storedVersion ?: LIFECYCLE_UNKNOWN,
                    storedBuild ?: LIFECYCLE_UNKNOWN
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
        val version = appContext.version
        val build = appContext.build

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
        analytics.track(LifecycleEventNames.BACKGROUNDED, emptyMap())
    }

    /**
     * Buffer deep-link properties for the next `Application Opened` event.
     * The buffer is one-shot — cleared after emission.
     *
     * Hosts should call this from the receiving Activity's `onCreate` / `onNewIntent`
     * (typically reading `intent.data` for the URI and `Activity.referrer?.host` for the
     * referrer — `Intent.EXTRA_REFERRER` is documented as a `Uri`, not a String, and
     * `getStringExtra` on it is virtually always `null`).
     */
    fun openURL(uri: Uri, sourceApplication: String?) {
        pendingDeepLink.set(DeepLink(uri.toString(), sourceApplication))
    }

    private fun emitInstalled(version: String, build: String) {
        analytics.track(
            LifecycleEventNames.INSTALLED,
            mapOf(
                LifecycleEventProperties.VERSION to version,
                LifecycleEventProperties.BUILD to build
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
            LifecycleEventNames.UPDATED,
            mapOf(
                LifecycleEventProperties.VERSION to version,
                LifecycleEventProperties.BUILD to build,
                LifecycleEventProperties.PREVIOUS_VERSION to previousVersion,
                LifecycleEventProperties.PREVIOUS_BUILD to previousBuild
            )
        )
    }

    private fun emitOpened(fromBackground: Boolean, version: String, build: String) {
        val props = mutableMapOf<String, Any?>(
            LifecycleEventProperties.VERSION to version,
            LifecycleEventProperties.BUILD to build,
            LifecycleEventProperties.FROM_BACKGROUND to fromBackground
        )
        pendingDeepLink.getAndSet(null)?.let { deepLink ->
            props[LifecycleEventProperties.URL] = deepLink.url
            deepLink.referringApplication?.let {
                props[LifecycleEventProperties.REFERRING_APPLICATION] = it
            }
        }
        analytics.track(LifecycleEventNames.OPENED, props)
    }

    private data class DeepLink(val url: String, val referringApplication: String?)

    companion object {
        private fun defaultForegroundCheck(): Boolean {
            return try {
                ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
            } catch (_: IllegalStateException) {
                // ProcessLifecycleOwner.get() throws ISE off the main thread.
                // Default to false (defer Opened) to avoid spurious double-emits.
                false
            } catch (_: NoClassDefFoundError) {
                // androidx.lifecycle:lifecycle-process not on the runtime classpath
                // (rare in production, can happen in test contexts that strip optional deps).
                false
            }
        }
    }
}

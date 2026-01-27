package com.metarouter.analytics.lifecycle

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.metarouter.analytics.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Observes app lifecycle events to trigger flush on background and pause/resume the dispatcher.
 *
 * @param scope Coroutine scope for executing suspend callbacks
 * @param onForeground Called when app comes to foreground (main thread, non-suspend)
 * @param onBackground Called when app goes to background (suspend, executed in scope)
 */
class AppLifecycleObserver(
    private val scope: CoroutineScope,
    private val onForeground: () -> Unit,
    private val onBackground: suspend () -> Unit
) : DefaultLifecycleObserver {

    // Lazy initialization to avoid issues in unit tests
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Register this observer with ProcessLifecycleOwner.
     * Must be called from the main thread.
     */
    fun register() {
        mainHandler.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            Logger.log("AppLifecycleObserver registered")
        }
    }

    /**
     * Unregister this observer from ProcessLifecycleOwner.
     * Call this during SDK reset to clean up.
     */
    fun unregister() {
        mainHandler.post {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
            Logger.log("AppLifecycleObserver unregistered")
        }
    }

    /**
     * Called when app comes to foreground.
     * Triggers flush and resumes dispatcher.
     * Wrapped in coroutine to avoid blocking main thread (consistent with onStop).
     */
    override fun onStart(owner: LifecycleOwner) {
        Logger.log("App moved to foreground")
        scope.launch { onForeground() }
    }

    /**
     * Called when app goes to background.
     * Triggers flush and pauses dispatcher.
     */
    override fun onStop(owner: LifecycleOwner) {
        Logger.log("App moved to background")
        scope.launch {
            onBackground()
        }
    }
}

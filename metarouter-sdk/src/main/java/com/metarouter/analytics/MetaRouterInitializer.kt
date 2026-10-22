package com.metarouter.analytics

import android.content.Context
import androidx.startup.Initializer
import com.metarouter.analytics.utils.Logger

/**
 * Hands the SDK a `Context` at process start, before `Application.onCreate`, the way
 * iOS has `UserDefaults` from the first instruction. Without it nothing persisted is
 * readable, and nothing written is saved, until the host calls `initialize` — a
 * consent withdrawal made before `initialize`, in a process that never reaches it,
 * would be lost.
 *
 * Registered with AndroidX App Startup's shared `InitializationProvider` (the library
 * manifest's meta-data, merged into the host's), not as a provider of its own: one
 * provider for every AndroidX-style initializer, and the standard per-initializer
 * opt-out. It does nothing but attach storage (a small SharedPreferences file) and
 * record whether the build is debuggable. A host that removes it falls back to the
 * attach at `initialize`.
 */
class MetaRouterInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        try {
            MetaRouter.onProcessStart(context)
        } catch (e: Throwable) {
            // Throwable, not Exception: the first touch of MetaRouter runs its static
            // initializer, and a failure there (a class stripped by R8, a mismatched
            // coroutines version) surfaces as an Error. The SDK must never take the
            // host's startup down; initialize attaches again.
            Logger.error("MetaRouter startup attach failed: ${e.message}", e)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

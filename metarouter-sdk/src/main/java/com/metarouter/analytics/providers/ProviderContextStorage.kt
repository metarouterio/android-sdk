package com.metarouter.analytics.providers

import android.content.Context
import com.metarouter.analytics.utils.Logger
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The provider → key → value map the host has set, persisted across launches as one
 * JSON blob.
 *
 * Persistence is the point, not a convenience: these values come from vendor SDKs that
 * resolve asynchronously (Firebase's `getAppInstanceId()` is a Task), while
 * `initialize()` emits the cold-launch events and mints the first session in its
 * background setup. A memory-only store would miss those events on *every* launch —
 * exactly the ones a vendor rejects when the id is absent.
 *
 * One blob rather than a key per provider: the map is replaced atomically, and a
 * half-written multi-key state would stamp a block nobody configured.
 *
 * Its own `SharedPreferences` file, outside identity, session and consent, so
 * [com.metarouter.analytics.storage.IdentityStorage.clear] — and therefore `reset()` —
 * never touches it: these are install-scoped vendor ids, not user state. A logout that
 * stopped the stamp would make every later event unforwardable.
 */
internal class ProviderContextStorage(context: Context, prefsName: String = PREFS_NAME) {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun get(): Map<String, JsonObject>? {
        return try {
            val raw = prefs.getString(KEY_CONTEXT, null) ?: return null
            Json.decodeFromString(serializer, raw)
        } catch (e: Exception) {
            // Corrupt state (or a non-string under the key) reads as never-set rather
            // than failing: stamping nothing beats refusing to start, and the host's
            // next set heals it.
            Logger.warn("Stored provider context unreadable — treating as unset: ${e.message}")
            null
        }
    }

    fun set(value: Map<String, JsonObject>) {
        prefs.edit().putString(KEY_CONTEXT, Json.encodeToString(serializer, value)).apply()
    }

    /**
     * Production path (`clearAllProviderContext`, and the last key of the last
     * provider), unlike `SessionStorage.clear()` which is a test-only seam.
     */
    fun clear() {
        prefs.edit().remove(KEY_CONTEXT).apply()
    }

    companion object {
        private const val PREFS_NAME = "com.metarouter.analytics.providers"

        // Byte-identical to the iOS SDK so cross-platform debugging reads one
        // vocabulary.
        const val KEY_CONTEXT = "metarouter:providers:context"

        private val serializer = MapSerializer(String.serializer(), JsonObject.serializer())
    }
}

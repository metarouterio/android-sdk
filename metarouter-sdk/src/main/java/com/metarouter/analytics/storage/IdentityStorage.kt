package com.metarouter.analytics.storage

import android.content.Context

/**
 * Thread-safe persistent storage for identity information.
 *
 * Uses SharedPreferences with apply() for non-blocking writes. SharedPreferences
 * guarantees that apply() calls are ordered and will be persisted, making this
 * safe for identity storage without risking ANRs on the main thread.
 */
class IdentityStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(key: String): String? = prefs.getString(key, null)

    /**
     * Store a value asynchronously (non-blocking).
     * The write is guaranteed to be ordered with other apply() calls and will persist.
     */
    fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "com.metarouter.analytics"

        const val KEY_ANONYMOUS_ID = "metarouter:anonymous_id"
        const val KEY_USER_ID = "metarouter:user_id"
        const val KEY_GROUP_ID = "metarouter:group_id"
        const val KEY_ADVERTISING_ID = "metarouter:advertising_id"
    }
}

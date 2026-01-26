package com.metarouter.analytics.storage

import android.content.Context

/**
 * Thread-safe persistent storage for identity information.
 * Wraps SharedPreferences with commit() for writes (durability) and apply() for removes (performance).
 */
class IdentityStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(key: String): String? = prefs.getString(key, null)

    fun set(key: String, value: String): Boolean =
        prefs.edit().putString(key, value).commit()

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

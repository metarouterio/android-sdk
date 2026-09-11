package com.metarouter.analytics.session

import android.content.Context

/**
 * Persists the current session id, the lifetime session counter, and the
 * wall-clock time of the last recorded activity, so a session can survive a
 * process restart that happens inside the inactivity window.
 *
 * Lives in a dedicated `SharedPreferences` file so [com.metarouter.analytics.storage.IdentityStorage.clear]
 * (and `reset()`) cannot end a session as a side effect of clearing identity:
 * a logout mid-session must not fragment the analytics session — downstream
 * session-scoped reporting (e.g. GA4) counts the visit, not the login state.
 *
 * `lastActivityMs` is deliberately wall-clock (epoch milliseconds): a
 * monotonic reading is meaningless across process restarts, which is the only
 * reason this value is persisted at all. In-process inactivity is measured
 * monotonically by [SessionManager] and never read back from here.
 */
class SessionStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSessionId(): String? = prefs.getString(KEY_SESSION_ID, null)

    /**
     * Null when absent — `getInt` with a sentinel default cannot distinguish
     * "never stored" from a stored value, and a fresh install must start its
     * counter at 1 instead of resuming a phantom count.
     */
    fun getSessionCount(): Int? =
        if (prefs.contains(KEY_SESSION_COUNT)) prefs.getInt(KEY_SESSION_COUNT, 0) else null

    fun getLastActivityMs(): Long? =
        if (prefs.contains(KEY_LAST_ACTIVITY_MS)) prefs.getLong(KEY_LAST_ACTIVITY_MS, 0L) else null

    fun setSession(id: String, count: Int, lastActivityMs: Long) {
        prefs.edit()
            .putString(KEY_SESSION_ID, id)
            .putInt(KEY_SESSION_COUNT, count)
            .putLong(KEY_LAST_ACTIVITY_MS, lastActivityMs)
            .apply()
    }

    fun setLastActivityMs(value: Long) {
        prefs.edit().putLong(KEY_LAST_ACTIVITY_MS, value).apply()
    }

    /**
     * Removes all persisted session state. Test-only seam — production code
     * must never call this. The dedicated preferences file exists so nothing —
     * not even `reset()` — can end a session as a side effect.
     */
    internal fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "com.metarouter.analytics.session"

        // Key strings are byte-identical to the iOS SDK so cross-platform
        // debugging reads one vocabulary.
        const val KEY_SESSION_ID = "metarouter:session:id"
        const val KEY_SESSION_COUNT = "metarouter:session:count"
        const val KEY_LAST_ACTIVITY_MS = "metarouter:session:last_activity_ms"
    }
}

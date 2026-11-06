package com.metarouter.analytics.storage

import android.content.Context
import android.content.SharedPreferences
import java.util.WeakHashMap

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
     * Returns true if any identity-related key is currently set in storage.
     * Used to distinguish a fresh install from an upgrade that pre-dates lifecycle tracking.
     * A host-set anonymousId on a device that had none does not count: the host can set
     * it before the first `initialize`, which is still an install.
     */
    fun hasAnyValue(): Boolean {
        return (prefs.contains(KEY_ANONYMOUS_ID) && !hasHostSetMarker()) ||
            prefs.contains(KEY_USER_ID) ||
            prefs.contains(KEY_GROUP_ID) ||
            prefs.contains(KEY_ADVERTISING_ID)
    }

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

    /**
     * Clears all identity fields. When it is the clear of a requested `reset()` (see
     * [noteResetRequested]), keeps a host-set anonymousId written after that request.
     */
    fun clear() {
        synchronized(anonymousIdLock) {
            val ordering = resetOrdering[prefs]
            val keepAnonymousId = ordering?.hostSetSinceRequest ?: false
            if (ordering != null && ordering.pending > 0) {
                ordering.pending--
                if (ordering.pending == 0) resetOrdering.remove(prefs)
            }
            // One definition of "everything": the editor applies clear() before the
            // puts, whatever the call order, so a kept id is written back over it.
            val edit = prefs.edit().clear()
            if (keepAnonymousId) {
                get(KEY_ANONYMOUS_ID)?.let { edit.putString(KEY_ANONYMOUS_ID, it) }
                if (hasHostSetMarker()) edit.putBoolean(KEY_HOST_SET_MARKER, true)
            }
            edit.apply()
        }
    }

    /** The stored anonymousId, or [make]'s value stored as the new one when none exists. */
    internal fun getOrCreateAnonymousId(make: () -> String): Pair<String, Boolean> {
        // Unlocked first: an id is almost always stored, and this runs per event.
        get(KEY_ANONYMOUS_ID)?.let { return it to false }
        return synchronized(anonymousIdLock) {
            get(KEY_ANONYMOUS_ID)?.let { return it to false }
            val id = make()
            set(KEY_ANONYMOUS_ID, id)
            id to true
        }
    }

    /**
     * Stores a host-provided anonymousId. Marks it host-set when the device had no
     * anonymousId yet, so [hasAnyValue] keeps reporting a fresh install.
     * @return the anonymousId it replaced, and whether anything was written (`false`
     *   when [id] is already stored).
     */
    internal fun setHostAnonymousId(id: String): Pair<String?, Boolean> =
        synchronized(anonymousIdLock) {
            // Before the unchanged check: re-setting the current id right after reset()
            // must keep it too.
            resetOrdering[prefs]?.hostSetSinceRequest = true
            val previous = get(KEY_ANONYMOUS_ID)
            if (previous == id) return previous to false
            val edit = prefs.edit().putString(KEY_ANONYMOUS_ID, id)
            if (previous == null) edit.putBoolean(KEY_HOST_SET_MARKER, true)
            edit.apply()
            previous to true
        }

    /**
     * Called when the host calls `reset()`, before the clear it leads to runs (later, or
     * on a replay at bind): from here, only a host id set after this call survives it.
     */
    internal fun noteResetRequested() {
        synchronized(anonymousIdLock) {
            val ordering = resetOrdering.getOrPut(prefs) { ResetOrdering() }
            ordering.pending++
            ordering.hostSetSinceRequest = false
        }
    }

    internal fun hasHostSetMarker(): Boolean = prefs.contains(KEY_HOST_SET_MARKER)

    /** Ends the host-set marker's window. Called once install detection has read it. */
    internal fun clearHostSetMarker() {
        synchronized(anonymousIdLock) { prefs.edit().remove(KEY_HOST_SET_MARKER).apply() }
    }

    /**
     * `reset()` ordering, per preferences instance and in memory only: the clear a reset
     * leads to runs later in the same process, and nothing survives a relaunch to order.
     * Guarded by [anonymousIdLock].
     */
    private class ResetOrdering {
        var pending = 0
        var hostSetSinceRequest = false
    }

    companion object {
        private const val PREFS_NAME = "com.metarouter.analytics"

        const val KEY_ANONYMOUS_ID = "metarouter:anonymous_id"
        const val KEY_USER_ID = "metarouter:user_id"
        const val KEY_GROUP_ID = "metarouter:group_id"
        const val KEY_ADVERTISING_ID = "metarouter:advertising_id"

        /**
         * Present from a host-set anonymousId on a device that had none until the next
         * client is built, so that launch's install detection still sees the device as
         * fresh. Bookkeeping, not identity, so not public.
         */
        private const val KEY_HOST_SET_MARKER = "metarouter:anonymous_id_host_set"

        // Process-wide: the proxy writes a host-set anonymousId through its own
        // instance while a client's IdentityManager may be minting one through another.
        // Both check-and-set under this lock so neither overwrites the other.
        private val anonymousIdLock = Any()

        // Keyed by the preferences instance (one per file per process), so separate
        // stores in tests never share it.
        private val resetOrdering = WeakHashMap<SharedPreferences, ResetOrdering>()
    }
}

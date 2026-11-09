package com.metarouter.analytics.identity

import com.metarouter.analytics.storage.IdentityStorage

/**
 * The proxy's handle on identity storage, for the host's `setAnonymousId` and the
 * synchronous half of `reset()` while no client is bound.
 *
 * Storage arrives after construction, like consent's: the process-wide instance is built
 * before any `Context` exists, and [attachStorage] runs at process start through
 * `MetaRouterInitializer` (or at `initialize`, when a host removed it). Until then the
 * calls are recorded in order and replayed at the attach, which precedes the client's
 * construction, so install detection and the first events see them as if storage had
 * been there from the start.
 *
 * @param storage null until [attachStorage]; tests pass one up front.
 */
internal class HostIdentity(storage: IdentityStorage? = null) {

    private sealed class Write {
        class SetAnonymousId(val id: String) : Write()
        object NoteReset : Write()
    }

    private val lock = Any()

    @Volatile
    private var storage: IdentityStorage? = storage

    private val preAttachWrites = mutableListOf<Write>()

    /** Whether [attachStorage] has run, so callers can skip building a storage. */
    val hasStorage: Boolean
        get() = storage != null

    fun attachStorage(newStorage: IdentityStorage) {
        synchronized(lock) {
            if (storage != null) return
            for (write in preAttachWrites) {
                when (write) {
                    is Write.SetAnonymousId -> newStorage.setHostAnonymousId(write.id)
                    Write.NoteReset -> newStorage.noteResetRequested()
                }
            }
            preAttachWrites.clear()
            storage = newStorage
        }
    }

    /** See `AnalyticsInterface.setAnonymousId`. */
    fun setAnonymousId(anonymousId: String): Boolean {
        synchronized(lock) {
            storage?.let { return IdentityManager.applyHostAnonymousId(anonymousId, it) }
            val pendingId = (preAttachWrites.lastOrNull() as? Write.SetAnonymousId)?.id
            val id = IdentityManager.validateHostAnonymousId(anonymousId, pendingId) ?: return false
            // Only the last of consecutive sets can matter; a reset note in between
            // keeps both, since the note decides which one a later clear keeps.
            if (pendingId != null) preAttachWrites.removeAt(preAttachWrites.lastIndex)
            preAttachWrites += Write.SetAnonymousId(id)
            return true
        }
    }

    /**
     * Test seam: the process-wide instance outlives tests, and Robolectric gives each
     * test a fresh application, so a storage attached by an earlier test points at
     * preferences no longer in use. Drops recorded writes too.
     */
    fun replaceStorageForTesting(newStorage: IdentityStorage?) {
        synchronized(lock) {
            storage = newStorage
            preAttachWrites.clear()
        }
    }

    /** See [IdentityStorage.noteResetRequested]. */
    fun noteResetRequested() {
        synchronized(lock) {
            storage?.let { return it.noteResetRequested() }
            preAttachWrites += Write.NoteReset
        }
    }
}

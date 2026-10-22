package com.metarouter.analytics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.metarouter.analytics.utils.Logger

/**
 * Hands the SDK a `Context` at process start, before `Application.onCreate`, the way
 * iOS has `UserDefaults` from the first instruction. Without it nothing persisted is
 * readable, and nothing written is saved, until the host calls `initialize` — a
 * consent withdrawal made before `initialize`, in a process that never reaches it,
 * would be lost.
 *
 * Declared in the library manifest and merged into the host's automatically. It does
 * nothing but attach storage (a small SharedPreferences file) and record whether the
 * build is debuggable; it serves no data and is not exported. A host that removes it
 * (`tools:node="remove"`) falls back to the attach at `initialize`.
 */
class MetaRouterInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false
        try {
            MetaRouter.onProcessStart(context)
        } catch (e: Exception) {
            // Nothing here is expected to throw (no host code has run yet, and storage
            // reads already degrade), but the SDK must never take the host's startup
            // down; initialize attaches again.
            Logger.error("MetaRouter startup attach failed: ${e.message}", e)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

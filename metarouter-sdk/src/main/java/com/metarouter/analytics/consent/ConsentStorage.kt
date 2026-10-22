package com.metarouter.analytics.consent

import android.content.Context
import com.metarouter.analytics.utils.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What [ConsentStorage] persists: the host's snapshot exactly as it arrived (after
 * stamping), plus the newest host-supplied timestamp seen since the last clear.
 *
 * The RAW snapshot is stored, never the reconciled view. Reconciliation is a
 * projection onto whatever registry is current, and persisting a projection loses
 * information: a build that registers fewer categories would silently delete the
 * user's decision for the ones it dropped, and a later build that registers them
 * again would read back a default the user never chose. Keeping the source of truth
 * raw means every registration recomputes the view losslessly.
 *
 * [lastHostTimestamp] is the stale-snapshot watermark. Only two host-supplied
 * timestamps are comparable (same clock, same unit), so it is a high-water mark of
 * host timestamps alone — an untimestamped set carries it forward untouched rather
 * than resetting it, otherwise a host re-pushing the same decision without a
 * timestamp would let an older CMP replay overwrite a newer explicit choice.
 */
@Serializable
internal data class StoredConsent(
    val consent: Consent,
    val lastHostTimestamp: Long? = null
)

/**
 * Persists the host's consent snapshot across launches as one JSON blob.
 *
 * One blob rather than one key per field: the state is replaced atomically on every
 * `setConsent`, and a partially written multi-key state would read back as a consent
 * decision nobody made.
 *
 * Lives in its own `SharedPreferences` file, outside identity and session, so
 * [com.metarouter.analytics.storage.IdentityStorage.clear] — and therefore `reset()` —
 * never touches it: consent is a compliance record scoped to the device and person,
 * not to the analytics identity. A logout must not resurrect tracking the user opted
 * out of.
 */
internal class ConsentStorage(context: Context, prefsName: String = PREFS_NAME) {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun get(): StoredConsent? {
        return try {
            val raw = prefs.getString(KEY_STATE, null) ?: return null
            json.decodeFromString(StoredConsent.serializer(), raw)
        } catch (e: Exception) {
            // Corrupt state (or a non-string under the key, which getString throws
            // on) reads as never-set — the implicit default — rather than failing:
            // wrong-but-safe beats unusable, and the next setConsent heals it.
            // Unknown keys are tolerated, so a downgrade past a build that ADDED a
            // field does not land here; a build that reshapes the blob would, and
            // owes a migration.
            Logger.warn("Stored consent state unreadable — treating as never set: ${e.message}")
            null
        }
    }

    fun set(stored: StoredConsent) {
        prefs.edit().putString(KEY_STATE, json.encodeToString(StoredConsent.serializer(), stored)).apply()
    }

    /**
     * Production path (the consent-withdrawal half of `clearConsent`), unlike
     * `SessionStorage.clear()` which is a test-only seam.
     */
    fun clear() {
        prefs.edit().remove(KEY_STATE).apply()
    }

    companion object {
        private const val PREFS_NAME = "com.metarouter.analytics.consent"

        // Byte-identical to the iOS SDK so cross-platform debugging reads one
        // vocabulary.
        const val KEY_STATE = "metarouter:consent:state"

        private val json = Json { ignoreUnknownKeys = true }
    }
}

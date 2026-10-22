package com.metarouter.analytics.consent

import kotlinx.serialization.Serializable

/**
 * Canonical consent state: what the host pushes in through `setConsent` and what the
 * SDK stores and stamps on outbound events. Field names and semantics are the web
 * SDK's `context.consent` block verbatim, so the server gates mobile and browser
 * traffic with one rule set — renaming a field here forks every per-integration
 * consent rule downstream.
 *
 * @property explicit `true` when the state reflects an explicit end-user choice (a
 *   banner interaction); `false` for an implicit or regional-default posture. The host
 *   sets it — only the host knows which CMP callback fired — and reconciliation
 *   carries it through untouched.
 * @property optOut Flat category map with opt-OUT semantics: `true` means blocked.
 *   Keys are lowercase category codes (`c0001`, `analytics`, …). Keys passed to
 *   `setConsent` are trimmed and lowercased before matching [ConsentCategory.key];
 *   if two collapse to one, opted-out wins.
 * @property timestamp Epoch milliseconds of the snapshot, as the host's CMP records
 *   it. Used for one thing: a `setConsent` carrying a timestamp older than the newest
 *   host-supplied timestamp since the last `clearConsent` is ignored, because CMP
 *   callbacks race app-launch reads and the newer choice must win regardless of
 *   arrival order. Only host timestamps are compared with each other. Leave null and
 *   the SDK stamps the device clock for information only — such a call is always
 *   applied. A value too large to be epoch milliseconds (micro- or nanoseconds) is
 *   treated as null, with a warning. Never part of the outbound payload.
 */
@Serializable
data class Consent(
    val explicit: Boolean,
    val optOut: Map<String, Boolean>,
    val timestamp: Long? = null
) {
    /**
     * Same decision, ignoring the clock. Subscribers react to this changing, never
     * to a fresher timestamp on identical values.
     */
    internal fun sameDecision(other: Consent): Boolean =
        explicit == other.explicit && optOut == other.optOut

    /** The block as `getDebugInfo()` reports it — the wire shape, no timestamp. */
    internal fun toDebugInfo(): Map<String, Any?> = mapOf(
        "explicit" to explicit,
        "optOut" to optOut
    )
}

/**
 * Canonical form of an `optOut` key: trimmed and lowercased. The one definition
 * both the registry and every vendor adapter normalize through, so a key can never
 * be canonical on one side and not the other.
 */
internal object ConsentKey {
    fun canonical(raw: String): String = raw.trim().lowercase()

    /**
     * Re-keys an `optOut` map canonically, dropping blank keys. Two raw keys that
     * collapse to one (`C0004` and `c0004`) resolve fail-closed — opted out wins —
     * because map iteration order is not something a host controls, and a coin flip
     * must never be what opts a user in.
     */
    fun canonicalize(optOut: Map<String, Boolean>): Map<String, Boolean> {
        val result = HashMap<String, Boolean>(optOut.size)
        for ((key, value) in optOut) {
            val canonicalKey = canonical(key)
            if (canonicalKey.isEmpty()) continue
            result[canonicalKey] = (result[canonicalKey] ?: false) || value
        }
        return result
    }
}

/**
 * A consent category the host registers at init, before any values arrive.
 *
 * A CMP payload is sparse and vendor-shaped — it will not list every category the
 * pipeline gates on. The registry is what lets the SDK complete an incoming snapshot
 * into a full block: known keys keep their value, unknown keys are dropped, missing
 * categories take [defaultOptOut].
 *
 * @property key Lowercase category code matching the keys in [Consent.optOut].
 *   Registration normalizes case and whitespace, so a `C0001` typo degrades to a
 *   warning rather than a category that silently never matches its incoming value.
 *   Not validated here: a throwing constructor would crash the host on a typo.
 * @property defaultOptOut Posture applied when an incoming snapshot does not mention
 *   this category. Defaults to `true` (blocked): a category is opted *in*
 *   deliberately, and the absence of a signal never opts a user in.
 */
data class ConsentCategory(
    val key: String,
    val defaultOptOut: Boolean = true
)

package com.metarouter.analytics.consent.adapters

import com.metarouter.analytics.consent.Consent
import com.metarouter.analytics.consent.ConsentKey
import com.metarouter.analytics.utils.Logger
import java.net.URLDecoder

/**
 * Translates a OneTrust consent payload into the canonical [Consent] shape.
 *
 * Pure translation: no state, no dependency on the OneTrust SDK, no I/O beyond a
 * diagnostic log when a cookie value yields nothing. The host reads OneTrust however it
 * likes — the native SDK's category-status API, or the `OptanonConsent` cookie's
 * `groups` parameter out of a WebView — and hands the raw statuses here, then passes
 * the result to `setConsent`. Reconciliation against the registered categories happens
 * there, not here: this adapter never adds a missing category or drops an unknown one,
 * so the host can register the set it gates on once and feed whatever OneTrust emits.
 *
 * OneTrust reports opt-IN status (1 / true = consented); the canonical shape is
 * opt-OUT. The inversion happens here, once, so no other layer ever reasons about
 * vendor semantics. Keys are lowercased (`C0004` → `c0004`) to match the web SDK's
 * `optOut` keys.
 */
object OneTrustAdapter {

    /**
     * @param raw OneTrust category statuses keyed by category ID, e.g.
     *   `mapOf("C0001" to 1, "C0002" to 0)`. Values may be `Boolean`, any number
     *   (`1` = consented), or a string (`"1"` / `"true"`, case-insensitive). A value
     *   that cannot be read as a consent status counts as opted out — an unreadable
     *   signal never opts a user in.
     * @param explicit whether this snapshot reflects an explicit user interaction (a
     *   banner choice) or a regional default. The payload cannot tell the two apart;
     *   the host knows which OneTrust callback fired.
     */
    @JvmStatic
    fun normalize(raw: Map<String, Any?>, explicit: Boolean): Consent {
        val optOut = HashMap<String, Boolean>(raw.size)
        for ((key, value) in raw) {
            optOut[key] = !isOptedIn(value)
        }
        return Consent(explicit = explicit, optOut = ConsentKey.canonicalize(optOut))
    }

    /**
     * Convenience for the `OptanonConsent` cookie's `groups` parameter, e.g.
     * `"C0001:1,C0002:0,C0004:1"`.
     *
     * Accepts the value as it actually comes out of a cookie store: still
     * percent-encoded (`C0001%3A1%2CC0002%3A0` — what `CookieManager.getCookie` and a
     * WebView's `document.cookie` hand back), the whole `OptanonConsent` value with the
     * `groups=` parameter somewhere inside it, or a full `document.cookie` string. The
     * `groups` token is isolated FIRST (on `&` and `;`) and only its value is
     * percent-decoded: decoding the whole string would let one stray `%` in an
     * unrelated cookie fail the decode and leave `groups` encoded, and would turn an
     * encoded `&` or `groups=` inside another parameter into a live delimiter. An
     * encoded value that parses to zero segments would make reconciliation fill every
     * category with its opt-out default — a user who accepted everything stamped as
     * explicitly rejecting everything.
     *
     * Segments without a `key:value` shape, or whose key contains `=` (a `name=` prefix
     * that is not a group ID), are skipped — a malformed value degrades to fewer
     * categories (which reconciliation fills with their opt-out defaults), never to a
     * crash or to a consented category. Input that yields no categories at all is
     * logged (the one side effect of this otherwise pure function), since that is
     * almost certainly the wrong field.
     */
    @JvmStatic
    fun normalize(groups: String, explicit: Boolean): Consent {
        val list = groupsList(groups)
        if (list == null) {
            if (groups.isNotBlank()) {
                Logger.warn(
                    "OneTrustAdapter: no `groups` parameter found — expected the OptanonConsent `groups` " +
                        "value, e.g. \"C0001:1,C0002:0\""
                )
            }
            return normalize(emptyMap(), explicit)
        }

        val raw = HashMap<String, Any?>()
        for (segment in list.split(',')) {
            val parts = segment.split(':', limit = 2)
            if (parts.size != 2) continue
            val key = parts[0].trim()
            val value = parts[1].trim()
            if (key.isEmpty() || key.contains('=')) continue
            raw[key] = value
        }

        if (raw.isEmpty() && list.isNotBlank()) {
            Logger.warn(
                "OneTrustAdapter: no category statuses found in groups value — expected the OptanonConsent " +
                    "`groups` parameter, e.g. \"C0001:1,C0002:0\""
            )
        }
        return normalize(raw, explicit)
    }

    /**
     * The decoded `groups` list out of whatever the host passed: a bare list, a cookie
     * value, or a cookie jar. Tokens are split on both `&` (query-style parameters
     * inside the cookie value) and `;` (cookie separators), so a `groups` that is the
     * last parameter of its cookie does not swallow the next cookie into its last
     * category. Null when the input is parameterized but carries no usable `groups`
     * token.
     *
     * - A `groups=` token whose value holds no `key:value` pair is skipped, so an
     *   unrelated cookie named `groups` in the jar cannot shadow OptanonConsent's.
     * - `&`-joined input without a usable `groups` token is never parsed whole: other
     *   OneTrust parameters (`hosts=H1:1,H2:0`) share the `key:value` shape and would
     *   otherwise come out as phantom categories.
     * - Anything else is a bare list — or a whole value encoded once more
     *   (`groups%3DC0001%253A1`), which is decoded and searched again.
     */
    private fun groupsList(input: String, depth: Int = 0): String? {
        for (token in input.split('&', ';')) {
            var trimmed = token.trim()
            // In a cookie jar, `groups` can be the first parameter of the cookie's
            // value: `OptanonConsent=groups=…`. Look past one `name=` prefix.
            if (!trimmed.startsWith(GROUPS_PREFIX)) {
                val eq = trimmed.indexOf('=')
                if (eq >= 0 && trimmed.startsWith(GROUPS_PREFIX, startIndex = eq + 1)) {
                    trimmed = trimmed.substring(eq + 1)
                }
            }
            if (!trimmed.startsWith(GROUPS_PREFIX)) continue
            val encoded = trimmed.substring(GROUPS_PREFIX.length)
            val decoded = percentDecoded(encoded) ?: encoded
            if (decoded.contains(':')) return decoded
        }
        if (input.contains('&')) return null
        val decoded = percentDecoded(input) ?: input
        if (depth == 0 && decoded.contains(GROUPS_PREFIX)) {
            return groupsList(decoded, depth = 1)
        }
        return decoded
    }

    /**
     * Percent-decoding only — `+` stays `+`, unlike form decoding — and null on a
     * malformed escape, so the caller keeps the raw value.
     */
    private fun percentDecoded(value: String): String? = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * `Boolean` is checked first on purpose: it must never fall through to the numeric
     * branch. Numbers compare as `Double`, so `1.5` cannot truncate into "consented"
     * while the same value of another numeric type does not.
     */
    private fun isOptedIn(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() == 1.0
        is String -> value.trim().let { it == "1" || it.equals("true", ignoreCase = true) }
        else -> false
    }

    private const val GROUPS_PREFIX = "groups="
}

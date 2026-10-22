package com.metarouter.analytics.consent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.consent.adapters.OneTrustAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric only for Logger (android.util.Log) on the no-categories warning path.
@RunWith(RobolectricTestRunner::class)
class OneTrustAdapterTest {

    // ===== Category map shape =====

    @Test
    fun `all allow inverts to all false`() {
        val consent = OneTrustAdapter.normalize(mapOf("C0001" to 1, "C0002" to 1, "C0004" to 1), explicit = true)

        assertEquals(mapOf("c0001" to false, "c0002" to false, "c0004" to false), consent.optOut)
        assertTrue(consent.explicit)
    }

    @Test
    fun `all deny inverts to all true`() {
        val consent = OneTrustAdapter.normalize(mapOf("C0001" to 0, "C0002" to 0, "C0004" to 0), explicit = true)

        assertEquals(mapOf("c0001" to true, "c0002" to true, "c0004" to true), consent.optOut)
    }

    @Test
    fun `partial consent`() {
        val consent = OneTrustAdapter.normalize(mapOf("C0001" to 1, "C0002" to 0, "C0004" to 1), explicit = true)

        assertEquals(mapOf("c0001" to false, "c0002" to true, "c0004" to false), consent.optOut)
    }

    @Test
    fun `keys are lowercased and trimmed`() {
        val consent = OneTrustAdapter.normalize(mapOf(" C0004 " to 1, "c0002" to 0, "" to 1), explicit = false)

        assertEquals("blank key dropped", mapOf("c0004" to false, "c0002" to true), consent.optOut)
    }

    @Test
    fun `explicit passes through unchanged`() {
        assertTrue(OneTrustAdapter.normalize(mapOf("C0001" to 1), explicit = true).explicit)
        assertFalse(OneTrustAdapter.normalize(mapOf("C0001" to 1), explicit = false).explicit)
    }

    @Test
    fun `empty input yields empty optOut`() {
        assertEquals(emptyMap<String, Boolean>(), OneTrustAdapter.normalize(emptyMap(), explicit = false).optOut)
    }

    @Test
    fun `timestamp is left for the SDK to stamp`() {
        assertNull(OneTrustAdapter.normalize(mapOf("C0001" to 1), explicit = true).timestamp)
    }

    // ===== Value representations =====

    @Test
    fun `boolean number and string representations of consented`() {
        val raw = mapOf<String, Any?>(
            "a" to true, "b" to 1, "c" to 1.0, "d" to 1L,
            "e" to "1", "f" to "true", "g" to "TRUE", "h" to " 1 "
        )

        val consent = OneTrustAdapter.normalize(raw, explicit = true)

        for (key in raw.keys) {
            assertEquals("$key must read as consented → optOut false", false, consent.optOut[key])
        }
    }

    @Test
    fun `boolean number and string representations of not consented`() {
        val raw = mapOf<String, Any?>(
            "a" to false, "b" to 0, "c" to 0.0, "d" to 0L,
            "e" to "0", "f" to "false", "g" to "FALSE", "h" to ""
        )

        val consent = OneTrustAdapter.normalize(raw, explicit = true)

        for (key in raw.keys) {
            assertEquals("$key must read as not consented → optOut true", true, consent.optOut[key])
        }
    }

    /** An unreadable signal never opts a user in. */
    @Test
    fun `unreadable values count as opted out`() {
        val raw = mapOf<String, Any?>(
            "a" to 2, "b" to -1, "c" to "yes", "d" to "on", "e" to null, "f" to listOf(1), "g" to mapOf("x" to 1), "h" to "1.0"
        )

        val consent = OneTrustAdapter.normalize(raw, explicit = true)

        for (key in raw.keys) {
            assertEquals("$key must degrade to opted out", true, consent.optOut[key])
        }
    }

    /**
     * A payload parsed from JSON by the host: booleans and numbers must read as a
     * status, and numbers other than 1 must not read as consented.
     */
    @Test
    fun `JSON-sourced values`() {
        val json = """{"C0001":1,"C0002":0,"C0003":true,"C0004":false,"C0005":7}"""
        val raw = Json.parseToJsonElement(json).jsonObject.mapValues { (_, value) ->
            val primitive = value.jsonPrimitive
            primitive.booleanOrNull ?: primitive.doubleOrNull
        }

        val consent = OneTrustAdapter.normalize(raw, explicit = true)

        assertEquals(
            mapOf("c0001" to false, "c0002" to true, "c0003" to false, "c0004" to true, "c0005" to true),
            consent.optOut
        )
    }

    /** Non-integral values must not truncate into "consented" in any numeric type. */
    @Test
    fun `non-integral float is not consented`() {
        val raw = mapOf<String, Any?>("a" to 1.5f, "b" to 1.5, "c" to 1f, "d" to 1.toByte(), "e" to 0L)

        val consent = OneTrustAdapter.normalize(raw, explicit = true)

        assertEquals(mapOf("a" to true, "b" to true, "c" to false, "d" to false, "e" to true), consent.optOut)
    }

    /** Two casings of one category must resolve the same way every run, and fail closed. */
    @Test
    fun `keys colliding after lowercasing resolve opted out`() {
        assertEquals(mapOf("c0004" to true), OneTrustAdapter.normalize(linkedMapOf("C0004" to 1, "c0004" to 0), explicit = true).optOut)
        assertEquals(mapOf("c0004" to true), OneTrustAdapter.normalize(linkedMapOf("c0004" to 0, "C0004" to 1), explicit = true).optOut)
    }

    // ===== OptanonConsent groups string =====

    @Test
    fun `groups string parses`() {
        val consent = OneTrustAdapter.normalize(groups = "C0001:1,C0002:0,C0004:1", explicit = true)

        assertEquals(mapOf("c0001" to false, "c0002" to true, "c0004" to false), consent.optOut)
    }

    /**
     * Segment by segment: surrounding whitespace is fine; an empty segment, a segment
     * with no colon, and a segment with an empty key are skipped; a value that is not a
     * status ("1:extra", "") reads as not consented.
     */
    @Test
    fun `groups string tolerates whitespace and malformed segments`() {
        val consent = OneTrustAdapter.normalize(
            groups = " C0001 : 1 , C0002:0,, garbage ,:1, C0004:1:extra ,C0005:",
            explicit = true
        )

        assertEquals(mapOf("c0001" to false, "c0002" to true, "c0004" to true, "c0005" to true), consent.optOut)
    }

    /**
     * What a cookie store actually hands back: still percent-encoded. Without the
     * decode this parsed to zero segments, and reconcile then stamped every registered
     * category as explicitly opted out.
     */
    @Test
    fun `percent-encoded groups value is decoded`() {
        val consent = OneTrustAdapter.normalize(groups = "C0001%3A1%2CC0002%3A1%2CC0004%3A0", explicit = true)

        assertEquals(mapOf("c0001" to false, "c0002" to false, "c0004" to true), consent.optOut)
    }

    @Test
    fun `whole OptanonConsent cookie value is accepted`() {
        val cookie = "isGpcEnabled=0&datestamp=Mon+Oct+05+2026&version=202409.1.0" +
            "&groups=C0001%3A1%2CC0002%3A0%2CC0004%3A1&AwaitingReconsent=false"

        val consent = OneTrustAdapter.normalize(groups = cookie, explicit = true)

        assertEquals(mapOf("c0001" to false, "c0002" to true, "c0004" to false), consent.optOut)
    }

    @Test
    fun `decoded whole cookie value is accepted`() {
        val consent = OneTrustAdapter.normalize(groups = "datestamp=x&groups=C0001:1,C0004:0&version=1", explicit = false)

        assertEquals(mapOf("c0001" to false, "c0004" to true), consent.optOut)
    }

    /**
     * A full document.cookie string: another cookie carrying a raw `%` must not break
     * the decode of the groups value, and a groups-last cookie must not swallow the next
     * cookie into its final category.
     */
    @Test
    fun `cookie jar with stray percent and groups last`() {
        val jar = "promo=100%; OptanonConsent=isGpcEnabled=0&groups=C0001%3A1%2CC0002%3A1%2CC0004%3A1; _ga=GA1.1.123.456"

        val consent = OneTrustAdapter.normalize(groups = jar, explicit = true)

        assertEquals(mapOf("c0001" to false, "c0002" to false, "c0004" to false), consent.optOut)
    }

    /** An encoded `&` or `groups=` inside another parameter must not become a live delimiter. */
    @Test
    fun `encoded delimiters in other parameters are ignored`() {
        val cookie = "landingPath=https%3A%2F%2Fx.com%2F%3Fgroups%3Dabc%26y%3D1&groups=C0001%3A1%2CC0004%3A0&version=1"

        val consent = OneTrustAdapter.normalize(groups = cookie, explicit = true)

        assertEquals(mapOf("c0001" to false, "c0004" to true), consent.optOut)
    }

    @Test
    fun `present but empty groups parameter yields empty optOut`() {
        assertEquals(
            emptyMap<String, Boolean>(),
            OneTrustAdapter.normalize(groups = "isGpcEnabled=0&groups=&AwaitingReconsent=false", explicit = true).optOut
        )
    }

    /** A `name=` prefix is not a group ID; the segment is dropped rather than minted as a category. */
    @Test
    fun `name-prefixed segment is dropped`() {
        val consent = OneTrustAdapter.normalize(groups = "OptanonConsent=C0001:1,C0002:0", explicit = true)

        assertEquals(mapOf("c0002" to true), consent.optOut)
    }

    @Test
    fun `empty groups string yields empty optOut`() {
        assertEquals(emptyMap<String, Boolean>(), OneTrustAdapter.normalize(groups = "", explicit = false).optOut)
    }

    /**
     * An unrelated cookie named `groups` earlier in the jar must not shadow
     * OptanonConsent's — that would stamp a user who accepted everything as rejecting
     * everything.
     */
    @Test
    fun `unrelated groups cookie does not shadow OptanonConsent`() {
        val jar = "groups=beta; OptanonConsent=isGpcEnabled=0&groups=C0001%3A1%2CC0002%3A1"

        val consent = OneTrustAdapter.normalize(groups = jar, explicit = true)

        assertEquals(mapOf("c0001" to false, "c0002" to false), consent.optOut)
    }

    /** Other OneTrust parameters share the `key:value` shape; without `groups` they must not become categories. */
    @Test
    fun `parameterized value without groups yields no categories`() {
        val consent = OneTrustAdapter.normalize(groups = "isGpcEnabled=0&hosts=H1:1,H2:0&genVendors=V1:1,V2:1", explicit = true)

        assertEquals(emptyMap<String, Boolean>(), consent.optOut)
    }

    /** In a jar, `groups` can be the first parameter of the cookie's own value. */
    @Test
    fun `groups as first parameter of a cookie in a jar is found`() {
        val jar = "OptanonConsent=groups=C0001%3A1%2CC0002%3A1&isGpcEnabled=0; other=1"

        val consent = OneTrustAdapter.normalize(groups = jar, explicit = true)

        assertEquals(mapOf("c0001" to false, "c0002" to false), consent.optOut)
    }

    @Test
    fun `whole value encoded twice is decoded and searched again`() {
        val consent = OneTrustAdapter.normalize(groups = "isGpcEnabled%3D0%26groups%3DC0001%253A1%252CC0002%253A0", explicit = true)

        assertEquals(mapOf("c0001" to false, "c0002" to true), consent.optOut)
    }

    /** Percent-decoding only: a literal `+` in a key is not turned into a space. */
    @Test
    fun `plus is not decoded as a space`() {
        assertEquals(mapOf("c+1" to false), OneTrustAdapter.normalize(groups = "C+1%3A1", explicit = true).optOut)
    }

    // ===== End to end through the SDK's reconcile =====

    @Test
    fun `output round trips through setConsent`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val storage = ConsentStorage(context).apply { clear() }
        try {
            val manager = ConsentManager(storage, dispatch = { })
            manager.registerConsentCategories(
                listOf(ConsentCategory("c0001", defaultOptOut = false), ConsentCategory("c0002"), ConsentCategory("c0004"))
            )

            // OneTrust reports two of the three registered categories plus one nobody tracks.
            manager.setConsent(OneTrustAdapter.normalize(mapOf("C0002" to 1, "C0004" to 0, "C0099" to 1), explicit = true))

            assertEquals(
                "adapter translates, setConsent reconciles: unknown dropped, missing filled",
                mapOf("c0001" to false, "c0002" to false, "c0004" to true),
                manager.getConsent().optOut
            )
            assertTrue(manager.getConsent().explicit)
        } finally {
            storage.clear()
        }
    }
}

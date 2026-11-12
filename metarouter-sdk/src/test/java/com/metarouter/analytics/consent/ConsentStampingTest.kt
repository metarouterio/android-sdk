package com.metarouter.analytics.consent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.context.DeviceContextProvider
import com.metarouter.analytics.enrichment.EventEnrichmentService
import com.metarouter.analytics.identity.IdentityManager
import com.metarouter.analytics.providers.ProviderContextManager
import com.metarouter.analytics.session.SessionManager
import com.metarouter.analytics.session.SessionStorage
import com.metarouter.analytics.types.BaseEvent
import com.metarouter.analytics.types.ConsentContext
import com.metarouter.analytics.types.EnrichedEventPayload
import com.metarouter.analytics.types.EventContext
import com.metarouter.analytics.types.EventType
import com.metarouter.analytics.types.LibraryContext
import com.metarouter.analytics.types.PageContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Payload-attach: every enriched event — whichever type or source produced it —
 * carries `context.consent` read from the consent manager at enrichment time, in the
 * web SDK's shape, and the stamp is point-in-time.
 */
@RunWith(RobolectricTestRunner::class)
class ConsentStampingTest {

    private lateinit var storage: ConsentStorage
    private lateinit var consentManager: ConsentManager
    private lateinit var service: EventEnrichmentService

    // The Dispatcher's wire encoder.
    private val wireJson = Json { encodeDefaults = true }

    private val registry = listOf(
        ConsentCategory("c0001", defaultOptOut = false),
        ConsentCategory("c0002"),
        ConsentCategory("c0004")
    )

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storage = ConsentStorage(context)
        storage.clear()
        consentManager = ConsentManager(storage, dispatch = { })

        val identityManager = mockk<IdentityManager> {
            coEvery { getAnonymousId() } returns "anon-1"
            every { getUserId() } returns null
            every { getGroupId() } returns null
            coEvery { getAdvertisingId() } returns null
        }
        val contextProvider = mockk<DeviceContextProvider> {
            every { getContext() } returns EventContext(library = LibraryContext("metarouter-android-sdk", "test"))
        }
        val sessionStorage = mockk<SessionStorage>(relaxUnitFun = true) {
            every { getSessionId() } returns null
            every { getSessionCount() } returns null
            every { getLastActivityMs() } returns null
        }
        service = EventEnrichmentService(
            identityManager = identityManager,
            contextProvider = contextProvider,
            writeKey = "test-key",
            sessionManager = SessionManager(sessionStorage, wallClockMillis = { 1_757_400_000_000L }, monotonicClockMillis = { 50_000L }),
            consentManager = consentManager,
            providerContextManager = ProviderContextManager(),
            networkMonitor = null
        )
    }

    @After
    fun tearDown() {
        storage.clear()
    }

    private fun enrich(type: EventType = EventType.TRACK, event: String? = "A"): EnrichedEventPayload =
        runBlocking { service.enrichEvent(BaseEvent(type = type, event = event)) }

    // ===== Stamp on every event type =====

    @Test
    fun `every event type stamps consent`() {
        consentManager.registerConsentCategories(registry)
        consentManager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false)))
        val expected = ConsentContext(explicit = true, optOut = mapOf("c0001" to false, "c0002" to false, "c0004" to true))

        for (type in EventType.values()) {
            assertEquals("$type must carry the consent block", expected, enrich(type).context.consent)
        }
    }

    /**
     * The bridge sink enriches a `BaseEvent` with page facts attached, so the stamp
     * must land there and must not clobber page or the session block.
     */
    @Test
    fun `bridge-shaped event carries consent and page`() {
        consentManager.registerConsentCategories(registry)

        val payload = runBlocking {
            service.enrichEvent(
                BaseEvent(
                    type = EventType.TRACK,
                    event = "Web Click",
                    properties = mapOf("sku" to JsonPrimitive("abc")),
                    page = PageContext(url = "https://shop.example.com/cart", title = "Cart")
                )
            )
        }

        assertEquals(mapOf("c0001" to false, "c0002" to true, "c0004" to true), payload.context.consent?.optOut)
        assertEquals(
            "consent stamping must not disturb the page block",
            "https://shop.example.com/cart", payload.context.page?.url
        )
        assertNotNull("consent stamping must not disturb the session block", payload.context.providers?.get("metarouter"))
    }

    // ===== State transitions =====

    @Test
    fun `before first set events carry implicit default`() {
        consentManager.registerConsentCategories(registry)

        assertEquals(
            ConsentContext(explicit = false, optOut = mapOf("c0001" to false, "c0002" to true, "c0004" to true)),
            enrich().context.consent
        )
    }

    @Test
    fun `with no registry and no set events carry empty implicit default`() {
        assertEquals(
            "a complete block, never an absent field",
            ConsentContext(explicit = false, optOut = emptyMap()), enrich().context.consent
        )
    }

    @Test
    fun `after setConsent events carry reconciled consent`() {
        consentManager.registerConsentCategories(registry)
        consentManager.setConsent(Consent(explicit = true, optOut = mapOf("c0004" to false, "unknown" to true)))

        assertEquals(
            ConsentContext(explicit = true, optOut = mapOf("c0001" to false, "c0002" to true, "c0004" to false)),
            enrich().context.consent
        )
    }

    @Test
    fun `after clearConsent events carry implicit default again`() {
        consentManager.registerConsentCategories(registry)
        consentManager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false, "c0004" to false)))
        enrich()

        consentManager.clearConsent()

        assertEquals(
            ConsentContext(explicit = false, optOut = mapOf("c0001" to false, "c0002" to true, "c0004" to true)),
            enrich(event = "B").context.consent
        )
    }

    /** Read-your-writes: a set followed immediately by an event stamps the new state. */
    @Test
    fun `set then event stamps new state`() {
        consentManager.registerConsentCategories(registry)
        consentManager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to false)))
        assertEquals(false, enrich().context.consent?.optOut?.get("c0002"))

        consentManager.setConsent(Consent(explicit = true, optOut = mapOf("c0002" to true)))
        assertEquals(true, enrich(event = "B").context.consent?.optOut?.get("c0002"))
    }

    /** Point-in-time: a later setConsent does not rewrite an already-enriched payload. */
    @Test
    fun `stamp is point in time`() {
        consentManager.registerConsentCategories(registry)
        consentManager.setConsent(Consent(explicit = true, optOut = mapOf("c0004" to false)))
        val before = enrich()

        consentManager.setConsent(Consent(explicit = true, optOut = mapOf("c0004" to true)))

        assertEquals(false, before.context.consent?.optOut?.get("c0004"))
    }

    // ===== Wire and disk shape =====

    private fun encoded(payload: EnrichedEventPayload): String =
        wireJson.encodeToString(EnrichedEventPayload.serializer(), payload)

    /**
     * Pins the wire form against the Dispatcher's encoder. Observed in pipeline
     * testing: ingest-side normalization can drop `"explicit": false` from stored
     * events — this proves the SDK always sends the complete block, so any absence
     * downstream is not ours.
     */
    @Test
    fun `explicit false and false values are present on the wire`() {
        consentManager.registerConsentCategories(registry)

        val json = encoded(enrich())

        assertTrue(json, json.contains(""""explicit":false"""))
        assertTrue(json, json.contains(""""c0001":false"""))
    }

    @Test
    fun `empty optOut encodes as empty object`() {
        val json = encoded(enrich())

        assertTrue(json, json.contains(""""consent":{"""))
        assertTrue(json, json.contains(""""optOut":{}"""))
    }

    @Test
    fun `timestamp never reaches the wire`() {
        // Distinct from the session clock, whose id is also epoch ms on the wire.
        consentManager.setConsent(Consent(explicit = true, optOut = mapOf("a" to true), timestamp = 1_757_398_765_432))

        val json = encoded(enrich())

        assertFalse("internal ordering timestamp leaked: $json", json.contains("1757398765432"))
    }

    @Test
    fun `consent lives under context not top level`() {
        consentManager.registerConsentCategories(registry)

        val root = Json.parseToJsonElement(encoded(enrich())).jsonObject

        assertNull("web parity is context.consent, not a top-level block", root["consent"])
        val consent = root.getValue("context").jsonObject.getValue("consent").jsonObject
        assertEquals(false, consent.getValue("explicit").jsonPrimitive.boolean)
        assertEquals(
            mapOf("c0001" to false, "c0002" to true, "c0004" to true),
            (consent.getValue("optOut") as JsonObject).mapValues { it.value.jsonPrimitive.boolean }
        )
    }

    /**
     * Enriched events are persisted to disk as JSON. A queue written by a build that
     * predates `context.consent` must still decode — otherwise every event queued
     * before an upgrade is silently discarded on the first launch after it.
     */
    @Test
    fun `context without consent key still decodes`() {
        val json = """
            {"app":{"name":"A","version":"1","build":"1","namespace":"com.example"},
             "device":{"manufacturer":"Google","model":"Pixel","name":"pixel","type":"android"},
             "library":{"name":"metarouter-android-sdk","version":"1.5.0"},
             "os":{"name":"Android","version":"14"},
             "screen":{"density":3.0,"width":1080,"height":2400},
             "locale":"en-US","timezone":"UTC"}
        """.trimIndent()

        val context = Json { ignoreUnknownKeys = true }.decodeFromString(EventContext.serializer(), json)

        assertNull(context.consent)
    }

    @Test
    fun `consent context round trips through disk`() {
        val original = ConsentContext(explicit = true, optOut = mapOf("c0001" to false, "c0004" to true))

        val decoded = wireJson.decodeFromString(ConsentContext.serializer(), wireJson.encodeToString(ConsentContext.serializer(), original))

        assertEquals(original, decoded)
    }
}

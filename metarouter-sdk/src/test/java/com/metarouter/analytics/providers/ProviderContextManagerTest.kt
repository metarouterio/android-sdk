package com.metarouter.analytics.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.storage.IdentityStorage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ProviderContextManager: the host-set map, its bounds, and persistence. A rejected
 * write returns false and leaves prior state alone; nothing throws.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderContextManagerTest {

    private lateinit var context: Context
    private lateinit var storage: ProviderContextStorage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = ProviderContextStorage(context).apply { clear() }
    }

    @After
    fun tearDown() {
        storage.clear()
        IdentityStorage(context).clear()
    }

    private fun makeManager() = ProviderContextManager(storage)

    private fun block(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            is String -> put(k, v)
            is Boolean -> put(k, v)
            is Number -> put(k, v)
        }
    }

    // ===== Basic set / get =====

    @Test
    fun `set stores under provider and key`() {
        val manager = makeManager()
        assertTrue(manager.setValue("googleFirebase", "appInstanceId", "abc123"))

        assertEquals(mapOf("googleFirebase" to block("appInstanceId" to "abc123")), manager.getAll())
    }

    @Test
    fun `multiple providers and keys coexist`() {
        val manager = makeManager()
        manager.setValue("googleFirebase", "appInstanceId", "abc123")
        manager.setValue("googleFirebase", "nonPersonalizedAds", true)
        manager.setValue("adjust", "adid", "adj-1")

        assertEquals(
            mapOf(
                "googleFirebase" to block("appInstanceId" to "abc123", "nonPersonalizedAds" to true),
                "adjust" to block("adid" to "adj-1")
            ),
            manager.getAll()
        )
    }

    @Test
    fun `set replaces existing key without disturbing siblings`() {
        val manager = makeManager()
        manager.setValue("googleFirebase", "appInstanceId", "first")
        manager.setValue("googleFirebase", "nonPersonalizedAds", false)
        manager.setValue("googleFirebase", "appInstanceId", "second")

        assertEquals(
            mapOf("googleFirebase" to block("appInstanceId" to "second", "nonPersonalizedAds" to false)),
            manager.getAll()
        )
    }

    @Test
    fun `names are trimmed but never recased`() {
        val manager = makeManager()
        manager.setValue("  googleFirebase  ", " appInstanceId ", "x")

        // Casing is load-bearing: the Firebase kit matches
        // context.providers.googleFirebase.appInstanceId verbatim.
        assertEquals(mapOf("googleFirebase" to block("appInstanceId" to "x")), manager.getAll())
    }

    @Test
    fun `non-string scalars round trip`() {
        val manager = makeManager()
        manager.setValue("p", "int", 7)
        manager.setValue("p", "double", 1.5)
        manager.setValue("p", "bool", true)

        assertEquals(block("int" to 7, "double" to 1.5, "bool" to true), manager.getAll()["p"])
    }

    // ===== Removal =====

    @Test
    fun `null value removes key`() {
        val manager = makeManager()
        manager.setValue("p", "a", "1")
        manager.setValue("p", "b", "2")
        assertTrue(manager.setValue("p", "a", null))

        assertEquals(mapOf("p" to block("b" to "2")), manager.getAll())
    }

    @Test
    fun `removing last key removes provider entirely`() {
        val manager = makeManager()
        manager.setValue("p", "a", "1")
        manager.setValue("p", "a", null)

        // Not `{"p": {}}` — an empty block would ship forever, indistinguishable from a
        // misconfigured one.
        assertEquals(emptyMap<String, JsonObject>(), manager.getAll())
    }

    @Test
    fun `explicit JSON null removes rather than stamping null`() {
        val manager = makeManager()
        manager.setValue("p", "a", "1")
        manager.setValue("p", "a", JsonNull)

        assertEquals(emptyMap<String, JsonObject>(), manager.getAll())
    }

    @Test
    fun `clear provider removes only that block`() {
        val manager = makeManager()
        manager.setValue("p", "a", "1")
        manager.setValue("q", "b", "2")
        manager.clear("p")

        assertEquals(mapOf("q" to block("b" to "2")), manager.getAll())
    }

    @Test
    fun `clearAll removes everything`() {
        val manager = makeManager()
        manager.setValue("p", "a", "1")
        manager.setValue("q", "b", "2")
        manager.clearAll()

        assertEquals(emptyMap<String, JsonObject>(), manager.getAll())
    }

    /** Same as `clear(provider)`: removing what is not there writes nothing. */
    @Test
    fun `removing an absent key is a no-op that does not persist`() {
        val manager = makeManager()

        assertTrue(manager.setValue("p", "absent", null))

        assertNull("nothing was ever written", storage.get())
    }

    /** Removal never throws — a blank name just has nothing to remove. */
    @Test
    fun `removal with a blank name is rejected quietly`() {
        val manager = makeManager()
        manager.setValue("p", "a", "1")

        assertFalse(manager.setValue("  ", "a", null))

        assertEquals(mapOf("p" to block("a" to "1")), manager.getAll())
    }

    @Test
    fun `clear of unknown provider is a no-op`() {
        val manager = makeManager()
        manager.setValue("p", "a", "1")
        manager.clear("absent")

        assertEquals(mapOf("p" to block("a" to "1")), manager.getAll())
    }

    // ===== Reserved namespace =====

    @Test
    fun `reserved metarouter provider is rejected`() {
        val manager = makeManager()
        assertFalse(manager.setValue("metarouter", "sessionID", "hijacked"))

        assertEquals(emptyMap<String, JsonObject>(), manager.getAll())
    }

    @Test
    fun `reserved check applies after trimming`() {
        val manager = makeManager()
        assertFalse(manager.setValue("  metarouter ", "sessionID", "hijacked"))

        assertEquals(emptyMap<String, JsonObject>(), manager.getAll())
    }

    /** Lookalikes would sit next to the SDK's block and read as ours downstream. */
    @Test
    fun `reserved check ignores case and separators`() {
        val manager = makeManager()
        for (name in listOf("Metarouter", "METAROUTER", "meta router", "meta_router", "Meta-Router")) {
            assertFalse(name, manager.setValue(name, "sessionID", "x"))
        }
        assertTrue("only the reserved name itself", manager.setValue("metarouterPartner", "id", "x"))
        assertEquals(setOf("metarouterPartner"), manager.getAll().keys)
    }

    // ===== Bounds =====

    @Test
    fun `blank names are rejected`() {
        val manager = makeManager()
        assertFalse(manager.setValue("   ", "a", "1"))
        assertFalse(manager.setValue("p", "  ", "1"))

        assertEquals(emptyMap<String, JsonObject>(), manager.getAll())
    }

    @Test
    fun `overlong names are rejected`() {
        val manager = makeManager()
        val longName = "a".repeat(ProviderContextManager.MAX_NAME_BYTES + 1)
        assertFalse(manager.setValue(longName, "a", "1"))
        assertFalse(manager.setValue("p", longName, "1"))

        assertEquals(emptyMap<String, JsonObject>(), manager.getAll())
    }

    @Test
    fun `name limit is measured in UTF-8 bytes not characters`() {
        val manager = makeManager()
        // 33 × "é" = 33 characters but 66 UTF-8 bytes, over a 64-byte budget.
        val name = "é".repeat(33)
        assertTrue(name.length < ProviderContextManager.MAX_NAME_BYTES)

        assertFalse(manager.setValue(name, "a", "1"))

        assertEquals("a char-count budget would have accepted this", emptyMap<String, JsonObject>(), manager.getAll())
    }

    @Test
    fun `name exactly at the byte limit is accepted`() {
        val manager = makeManager()
        val name = "a".repeat(ProviderContextManager.MAX_NAME_BYTES)
        manager.setValue(name, "a", "1")

        assertEquals(block("a" to "1"), manager.getAll()[name])
    }

    @Test
    fun `oversize value is rejected and leaves prior value intact`() {
        val manager = makeManager()
        manager.setValue("p", "a", "keep-me")
        val big = "x".repeat(ProviderContextManager.MAX_VALUE_BYTES + 1)
        assertFalse(manager.setValue("p", "a", big))

        // Rejected, never truncated: a trimmed vendor id looks valid and matches nothing.
        assertEquals(mapOf("p" to block("a" to "keep-me")), manager.getAll())
    }

    @Test
    fun `value exactly at the byte limit is accepted`() {
        val manager = makeManager()
        // Two of the encoded bytes are the JSON quotes, which the budget counts.
        val value = "x".repeat(ProviderContextManager.MAX_VALUE_BYTES - 2)
        manager.setValue("p", "a", value)

        assertEquals(mapOf("p" to block("a" to value)), manager.getAll())
    }

    @Test
    fun `provider count is capped`() {
        val manager = makeManager()
        repeat(ProviderContextManager.MAX_PROVIDERS) { manager.setValue("p$it", "a", "1") }
        assertEquals(ProviderContextManager.MAX_PROVIDERS, manager.getAll().size)

        assertFalse(manager.setValue("overflow", "a", "1"))

        assertEquals(ProviderContextManager.MAX_PROVIDERS, manager.getAll().size)
        assertNull(manager.getAll()["overflow"])
    }

    @Test
    fun `provider cap does not block writes to providers already present`() {
        val manager = makeManager()
        repeat(ProviderContextManager.MAX_PROVIDERS) { manager.setValue("p$it", "a", "1") }
        manager.setValue("p0", "b", "2")

        assertEquals(block("a" to "1", "b" to "2"), manager.getAll()["p0"])
    }

    @Test
    fun `keys per provider are capped`() {
        val manager = makeManager()
        repeat(ProviderContextManager.MAX_KEYS_PER_PROVIDER) { manager.setValue("p", "k$it", "1") }
        assertEquals(ProviderContextManager.MAX_KEYS_PER_PROVIDER, manager.getAll()["p"]?.size)

        assertFalse(manager.setValue("p", "overflow", "1"))

        assertEquals(ProviderContextManager.MAX_KEYS_PER_PROVIDER, manager.getAll()["p"]?.size)
        assertNull(manager.getAll()["p"]?.get("overflow"))
    }

    @Test
    fun `key cap does not block overwriting an existing key`() {
        val manager = makeManager()
        repeat(ProviderContextManager.MAX_KEYS_PER_PROVIDER) { manager.setValue("p", "k$it", "1") }
        manager.setValue("p", "k0", "updated")

        assertEquals(JsonPrimitive("updated"), manager.getAll()["p"]?.get("k0"))
    }

    @Test
    fun `removal is not blocked by a full map`() {
        val manager = makeManager()
        repeat(ProviderContextManager.MAX_KEYS_PER_PROVIDER) { manager.setValue("p", "k$it", "1") }
        manager.setValue("p", "k0", null)

        assertEquals(ProviderContextManager.MAX_KEYS_PER_PROVIDER - 1, manager.getAll()["p"]?.size)
    }

    /**
     * The tolerant converter used for event properties stringifies what it does not
     * know; here that would stamp a value the host never set on every event.
     */
    @Test
    fun `container with an unrepresentable element is rejected whole`() {
        val manager = makeManager()
        manager.setValue("p", "a", "keep-me")
        assertFalse(manager.setValue("p", "a", mapOf("id" to "abc", "ts" to Date())))
        assertFalse(manager.setValue("p", "b", listOf("ok", Date())))
        assertFalse("non-string map key", manager.setValue("p", "c", mapOf(1 to "x")))

        assertEquals(mapOf("p" to block("a" to "keep-me")), manager.getAll())
    }

    @Test
    fun `fully representable containers are accepted`() {
        val manager = makeManager()
        manager.setValue("p", "a", mapOf("id" to "abc", "n" to 2))

        assertEquals(block("id" to "abc", "n" to 2), manager.getAll()["p"]?.get("a"))
    }

    @Test
    fun `unrepresentable value is rejected`() {
        val manager = makeManager()
        assertFalse(manager.setValue("p", "a", Date()))
        assertFalse("not valid JSON", manager.setValue("p", "b", Double.NaN))
        assertFalse("not valid JSON", manager.setValue("p", "c", Float.POSITIVE_INFINITY))

        assertEquals(emptyMap<String, JsonObject>(), manager.getAll())
    }

    /** A Java host can call clear() on any Map; the one handed out must refuse it. */
    @Test
    fun `map handed out is read-only`() {
        val manager = makeManager()
        manager.setValue("p", "a", "1")

        try {
            @Suppress("UNCHECKED_CAST")
            (manager.getAll() as MutableMap<String, JsonObject>).clear()
            fail("the snapshot must not be writable")
        } catch (expected: UnsupportedOperationException) {
        }
        assertEquals(mapOf("p" to block("a" to "1")), manager.getAll())
    }

    // ===== Persistence =====

    @Test
    fun `values survive a new manager over the same storage`() {
        makeManager().setValue("googleFirebase", "appInstanceId", "abc123")

        // A fresh manager over the same storage stands in for the next launch.
        assertEquals(
            mapOf("googleFirebase" to block("appInstanceId" to "abc123")),
            ProviderContextManager(storage).getAll()
        )
    }

    @Test
    fun `removal survives a new manager`() {
        val first = makeManager()
        first.setValue("p", "a", "1")
        first.setValue("q", "b", "2")
        first.clear("p")

        assertEquals(mapOf("q" to block("b" to "2")), ProviderContextManager(storage).getAll())
    }

    @Test
    fun `clearAll wipes persisted state`() {
        val first = makeManager()
        first.setValue("p", "a", "1")
        first.clearAll()

        assertNull(storage.get())
        assertEquals(emptyMap<String, JsonObject>(), ProviderContextManager(storage).getAll())
    }

    @Test
    fun `rejected write is not persisted`() {
        makeManager().setValue("metarouter", "sessionID", "x")

        assertNull(storage.get())
    }

    @Test
    fun `corrupt stored state reads as unset`() {
        context.getSharedPreferences("com.metarouter.analytics.providers", Context.MODE_PRIVATE)
            .edit().putString(ProviderContextStorage.KEY_CONTEXT, "not json").commit()

        // Wrong-but-safe beats unusable; the host's next set heals the blob.
        assertEquals(emptyMap<String, JsonObject>(), ProviderContextManager(storage).getAll())
    }

    /** The blob iOS writes reads back here unchanged. */
    @Test
    fun `reads the iOS blob shape`() {
        context.getSharedPreferences("com.metarouter.analytics.providers", Context.MODE_PRIVATE)
            .edit().putString(ProviderContextStorage.KEY_CONTEXT, """{"googleFirebase":{"appInstanceId":"abc123","n":2}}""")
            .commit()

        assertEquals(mapOf("googleFirebase" to block("appInstanceId" to "abc123", "n" to 2)), ProviderContextManager(storage).getAll())
    }

    @Test
    fun `storage uses its own namespace so identity clear cannot touch it`() {
        val manager = makeManager()
        manager.setValue("googleFirebase", "appInstanceId", "abc123")

        IdentityStorage(context).clear()

        // reset() clears identity; these are install-scoped, so a logout must not stop
        // events being forwardable.
        assertEquals(mapOf("googleFirebase" to block("appInstanceId" to "abc123")), manager.getAll())
        assertNotNull(storage.get())
    }

    @Test
    fun `key uses expected namespace`() {
        assertEquals("metarouter:providers:context", ProviderContextStorage.KEY_CONTEXT)
    }

    // ===== Storage attached at initialize =====

    @Test
    fun `nothing is persisted before attach`() {
        val manager = ProviderContextManager()

        assertTrue(manager.setValue("p", "a", "1"))

        assertNull(storage.get())
        assertEquals(mapOf("p" to block("a" to "1")), manager.getAll())
    }

    @Test
    fun `attach with no earlier writes loads the persisted map`() {
        storage.set(mapOf("googleFirebase" to block("appInstanceId" to "from-last-launch")))
        val manager = ProviderContextManager()
        assertEquals("nothing persisted is visible before the attach", emptyMap<String, JsonObject>(), manager.getAll())

        manager.attachStorage(storage)

        assertEquals(mapOf("googleFirebase" to block("appInstanceId" to "from-last-launch")), manager.getAll())
    }

    /** Writes before attach replay over the persisted map — newest wins, siblings survive. */
    @Test
    fun `writes before attach replay over the persisted map and persist`() {
        storage.set(
            mapOf(
                "googleFirebase" to block("appInstanceId" to "old", "nonPersonalizedAds" to true),
                "adjust" to block("adid" to "adj-1")
            )
        )
        val manager = ProviderContextManager()
        manager.setValue("googleFirebase", "appInstanceId", "new")
        manager.clear("adjust")

        manager.attachStorage(storage)

        val expected = mapOf("googleFirebase" to block("appInstanceId" to "new", "nonPersonalizedAds" to true))
        assertEquals(expected, manager.getAll())
        assertEquals(expected, storage.get())
    }

    /** A removal that found nothing in memory still reaches the persisted key. */
    @Test
    fun `removal before attach removes a persisted key`() {
        storage.set(mapOf("p" to block("a" to "1", "b" to "2")))
        val manager = ProviderContextManager()
        assertTrue(manager.setValue("p", "a", null))

        manager.attachStorage(storage)

        assertEquals(mapOf("p" to block("b" to "2")), storage.get())
    }

    @Test
    fun `clearAll before attach wipes the persisted map`() {
        storage.set(mapOf("p" to block("a" to "1")))
        val manager = ProviderContextManager()
        manager.clearAll()
        manager.setValue("q", "b", "2")

        manager.attachStorage(storage)

        assertEquals(mapOf("q" to block("b" to "2")), storage.get())
    }

    /** The bounds hold across the attach: a pre-attach write over the persisted cap is dropped there. */
    @Test
    fun `replay at attach applies the provider cap against the persisted map`() {
        storage.set((0 until ProviderContextManager.MAX_PROVIDERS).associate { "p$it" to block("a" to "1") })
        val manager = ProviderContextManager()
        assertTrue("the persisted map is not known yet", manager.setValue("overflow", "a", "1"))

        manager.attachStorage(storage)

        assertNull(manager.getAll()["overflow"])
        assertEquals(ProviderContextManager.MAX_PROVIDERS, manager.getAll().size)
    }

    @Test
    fun `second attach is ignored`() {
        val manager = ProviderContextManager()
        manager.attachStorage(storage)
        val other = ProviderContextStorage(context, prefsName = "com.metarouter.analytics.providers.test-other")
        other.set(mapOf("z" to block("a" to "1")))
        try {
            manager.attachStorage(other)
            manager.setValue("p", "a", "1")

            assertEquals(mapOf("p" to block("a" to "1")), manager.getAll())
            assertEquals(mapOf("p" to block("a" to "1")), storage.get())
            assertEquals("the second storage is never written", mapOf("z" to block("a" to "1")), other.get())
        } finally {
            other.clear()
        }
    }

    // ===== Concurrency =====

    @Test
    fun `concurrent writes all land and disk ends matching memory`() {
        val manager = makeManager()
        val count = 8
        val pool = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        repeat(count) { i ->
            pool.execute {
                start.await()
                manager.setValue("p", "k$i", "v$i")
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

        val inMemory = manager.getAll()
        assertEquals(count, inMemory["p"]?.size)
        // The persist sequence stops two threads leaving the lock out of order from
        // leaving disk on an older map than memory.
        assertEquals(inMemory, storage.get())
    }

    // ===== Debug info =====

    @Test
    fun `debug info reports shape without values`() {
        val manager = makeManager()
        manager.setValue("googleFirebase", "appInstanceId", "secret-id")
        manager.setValue("googleFirebase", "nonPersonalizedAds", true)

        // Vendor device identifiers, and getDebugInfo is printed into host logs.
        assertEquals(mapOf("googleFirebase" to listOf("appInstanceId", "nonPersonalizedAds")), manager.toDebugInfo())
    }
}

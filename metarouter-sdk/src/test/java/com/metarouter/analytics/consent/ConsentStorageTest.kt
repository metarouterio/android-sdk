package com.metarouter.analytics.consent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.storage.IdentityStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsentStorageTest {

    private lateinit var context: Context
    private lateinit var storage: ConsentStorage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = ConsentStorage(context)
        storage.clear()
    }

    @After
    fun tearDown() {
        storage.clear()
        IdentityStorage(context).clear()
    }

    private fun rawPrefs() = context.getSharedPreferences("com.metarouter.analytics.consent", Context.MODE_PRIVATE)

    @Test
    fun `round trip with timestamp`() {
        assertNull(storage.get())

        val stored = StoredConsent(
            consent = Consent(explicit = true, optOut = mapOf("c0001" to false, "c0004" to true), timestamp = 1_757_400_000_000),
            lastHostTimestamp = 1_757_400_000_000
        )
        storage.set(stored)

        assertEquals(stored, storage.get())
    }

    @Test
    fun `round trip without timestamp`() {
        val stored = StoredConsent(consent = Consent(explicit = false, optOut = emptyMap()), lastHostTimestamp = null)
        storage.set(stored)

        val read = storage.get()
        assertEquals(stored, read)
        assertNull(read?.consent?.timestamp)
    }

    @Test
    fun `set replaces prior state`() {
        storage.set(StoredConsent(Consent(explicit = true, optOut = mapOf("c0001" to false, "c0002" to false))))
        storage.set(StoredConsent(Consent(explicit = true, optOut = mapOf("c0001" to true))))

        assertEquals("replace, not merge", mapOf("c0001" to true), storage.get()?.consent?.optOut)
    }

    @Test
    fun `clear removes state`() {
        storage.set(StoredConsent(Consent(explicit = true, optOut = mapOf("c0001" to false))))
        storage.clear()

        assertNull(storage.get())
        assertFalse(rawPrefs().contains(ConsentStorage.KEY_STATE))
    }

    /**
     * Corrupt persisted state must read as never-set, not crash and not throw: the
     * implicit default is wrong-but-safe, and the next setConsent heals it.
     */
    @Test
    fun `corrupt state reads as never set`() {
        rawPrefs().edit().putString(ConsentStorage.KEY_STATE, "not json").commit()

        assertNull(storage.get())
    }

    @Test
    fun `wrong type under key reads as never set`() {
        rawPrefs().edit().putInt(ConsentStorage.KEY_STATE, 42).commit()

        assertNull(storage.get())
    }

    /**
     * A build that adds a field to the stored blob must not strand the previous build's
     * users on the implicit default after a downgrade.
     */
    @Test
    fun `unknown fields are tolerated`() {
        val json = """{"consent":{"explicit":true,"optOut":{"c0001":false},"timestamp":5,"future":1},""" +
            """"lastHostTimestamp":5,"futureField":"x"}"""
        rawPrefs().edit().putString(ConsentStorage.KEY_STATE, json).commit()

        assertEquals(
            StoredConsent(Consent(explicit = true, optOut = mapOf("c0001" to false), timestamp = 5), lastHostTimestamp = 5),
            storage.get()
        )
    }

    /**
     * The blob iOS writes reads back here unchanged: one shape on both platforms, so a
     * stored state is debuggable with one vocabulary.
     */
    @Test
    fun `reads the iOS blob shape`() {
        val json = """{"consent":{"explicit":false,"optOut":{"c0004":true},"timestamp":9}}"""
        rawPrefs().edit().putString(ConsentStorage.KEY_STATE, json).commit()

        assertEquals(
            StoredConsent(Consent(explicit = false, optOut = mapOf("c0004" to true), timestamp = 9), lastHostTimestamp = null),
            storage.get()
        )
    }

    /**
     * Consent lives outside the identity preferences so that `reset()` — which clears
     * identity — can never resurrect tracking the user opted out of.
     */
    @Test
    fun `identity storage clear does not touch consent`() {
        val identityStorage = IdentityStorage(context)
        identityStorage.set(IdentityStorage.KEY_ANONYMOUS_ID, "abc")
        identityStorage.set(IdentityStorage.KEY_USER_ID, "user-1")

        val stored = StoredConsent(Consent(explicit = true, optOut = mapOf("c0004" to true), timestamp = 1), lastHostTimestamp = 1)
        storage.set(stored)

        identityStorage.clear()

        assertNull("identity cleared", identityStorage.get(IdentityStorage.KEY_ANONYMOUS_ID))
        assertNull("identity cleared", identityStorage.get(IdentityStorage.KEY_USER_ID))
        assertEquals("consent must survive IdentityStorage.clear()", stored, storage.get())
    }

    @Test
    fun `key uses expected namespace`() {
        assertEquals("metarouter:consent:state", ConsentStorage.KEY_STATE)
    }
}

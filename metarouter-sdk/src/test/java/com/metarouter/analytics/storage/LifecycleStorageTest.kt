package com.metarouter.analytics.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LifecycleStorageTest {

    private lateinit var context: Context
    private lateinit var storage: LifecycleStorage
    private lateinit var identityStorage: IdentityStorage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = LifecycleStorage(context)
        identityStorage = IdentityStorage(context)
        storage.clear()
        identityStorage.clear()
    }

    @After
    fun tearDown() {
        storage.clear()
        identityStorage.clear()
    }

    @Test
    fun `get returns null when not set`() {
        assertNull(storage.getVersion())
        assertNull(storage.getBuild())
    }

    @Test
    fun `setVersionBuild persists both fields`() {
        storage.setVersionBuild("1.4.0", "42")

        assertEquals("1.4.0", storage.getVersion())
        assertEquals("42", storage.getBuild())
    }

    @Test
    fun `setVersionBuild overwrites previous values`() {
        storage.setVersionBuild("1.0.0", "1")
        storage.setVersionBuild("2.0.0", "2")

        assertEquals("2.0.0", storage.getVersion())
        assertEquals("2", storage.getBuild())
    }

    @Test
    fun `data persists across instances`() {
        storage.setVersionBuild("1.4.0", "42")

        val storage2 = LifecycleStorage(context)
        assertEquals("1.4.0", storage2.getVersion())
        assertEquals("42", storage2.getBuild())
    }

    @Test
    fun `lifecycle storage is independent of identity storage clear`() {
        storage.setVersionBuild("1.4.0", "42")
        identityStorage.set(IdentityStorage.KEY_ANONYMOUS_ID, "anon-id")
        identityStorage.set(IdentityStorage.KEY_USER_ID, "user-id")

        // Clearing identity storage must NOT touch lifecycle storage
        identityStorage.clear()

        assertNull(identityStorage.get(IdentityStorage.KEY_ANONYMOUS_ID))
        assertNull(identityStorage.get(IdentityStorage.KEY_USER_ID))
        assertEquals("1.4.0", storage.getVersion())
        assertEquals("42", storage.getBuild())
    }

    @Test
    fun `keys match cross-platform contract`() {
        assertEquals("metarouter:lifecycle:version", LifecycleStorage.KEY_VERSION)
        assertEquals("metarouter:lifecycle:build", LifecycleStorage.KEY_BUILD)
    }

    @Test
    fun `clear removes both fields`() {
        storage.setVersionBuild("1.4.0", "42")

        storage.clear()

        assertNull(storage.getVersion())
        assertNull(storage.getBuild())
    }

    @Test
    fun `identityStorage hasAnyValue reflects whether keys exist`() {
        // Empty initially
        assertEquals(false, identityStorage.hasAnyValue())

        identityStorage.set(IdentityStorage.KEY_ANONYMOUS_ID, "anon")
        assertEquals(true, identityStorage.hasAnyValue())

        identityStorage.clear()
        assertEquals(false, identityStorage.hasAnyValue())

        identityStorage.set(IdentityStorage.KEY_USER_ID, "user")
        assertEquals(true, identityStorage.hasAnyValue())
    }
}

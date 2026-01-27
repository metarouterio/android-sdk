package com.metarouter.analytics.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_ANONYMOUS_ID
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_GROUP_ID
import com.metarouter.analytics.storage.IdentityStorage.Companion.KEY_USER_ID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for IdentityStorage class.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityStorageTest {

    private lateinit var storage: IdentityStorage
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = IdentityStorage(context)
        storage.clear()
    }

    @After
    fun teardown() {
        storage.clear()
    }

    @Test
    fun `get returns null when key not set`() {
        assertNull(storage.get(KEY_ANONYMOUS_ID))
        assertNull(storage.get(KEY_USER_ID))
        assertNull(storage.get(KEY_GROUP_ID))
    }

    @Test
    fun `set stores and get retrieves value`() {
        storage.set(KEY_ANONYMOUS_ID, "test-id")
        assertEquals("test-id", storage.get(KEY_ANONYMOUS_ID))
    }

    @Test
    fun `setSync stores value and returns true`() {
        val stored = storage.setSync(KEY_ANONYMOUS_ID, "test-id-sync")
        assertTrue(stored)
        assertEquals("test-id-sync", storage.get(KEY_ANONYMOUS_ID))
    }

    @Test
    fun `set overwrites previous value`() {
        storage.set(KEY_USER_ID, "first")
        storage.set(KEY_USER_ID, "second")
        assertEquals("second", storage.get(KEY_USER_ID))
    }

    @Test
    fun `remove clears value`() {
        storage.set(KEY_USER_ID, "user-123")
        storage.remove(KEY_USER_ID)
        assertNull(storage.get(KEY_USER_ID))
    }

    @Test
    fun `remove is idempotent`() {
        storage.set(KEY_USER_ID, "user-123")
        storage.remove(KEY_USER_ID)
        storage.remove(KEY_USER_ID) // Should not throw
        assertNull(storage.get(KEY_USER_ID))
    }

    @Test
    fun `clear removes all data`() {
        storage.set(KEY_ANONYMOUS_ID, "anon-123")
        storage.set(KEY_USER_ID, "user-123")
        storage.set(KEY_GROUP_ID, "group-456")

        storage.clear()

        assertNull(storage.get(KEY_ANONYMOUS_ID))
        assertNull(storage.get(KEY_USER_ID))
        assertNull(storage.get(KEY_GROUP_ID))
    }

    @Test
    fun `clear is idempotent`() {
        storage.set(KEY_USER_ID, "user-123")
        storage.clear()
        storage.clear() // Should not throw
        assertNull(storage.get(KEY_USER_ID))
    }

    @Test
    fun `data persists across instances`() {
        val storage1 = IdentityStorage(context)
        storage1.set(KEY_ANONYMOUS_ID, "anon-persistent")
        storage1.set(KEY_USER_ID, "user-persistent")
        storage1.set(KEY_GROUP_ID, "group-persistent")

        val storage2 = IdentityStorage(context)
        assertEquals("anon-persistent", storage2.get(KEY_ANONYMOUS_ID))
        assertEquals("user-persistent", storage2.get(KEY_USER_ID))
        assertEquals("group-persistent", storage2.get(KEY_GROUP_ID))
    }

    @Test
    fun `storage keys match spec`() {
        assertEquals("metarouter:anonymous_id", KEY_ANONYMOUS_ID)
        assertEquals("metarouter:user_id", KEY_USER_ID)
        assertEquals("metarouter:group_id", KEY_GROUP_ID)
    }

    @Test
    fun `handles UUID format`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        storage.set(KEY_ANONYMOUS_ID, uuid)
        assertEquals(uuid, storage.get(KEY_ANONYMOUS_ID))
    }

    @Test
    fun `handles long strings`() {
        val longId = "a".repeat(1000)
        storage.set(KEY_USER_ID, longId)
        assertEquals(longId, storage.get(KEY_USER_ID))
    }

    @Test
    fun `handles special characters`() {
        val specialId = "user-123!@#\$%^&*()_+-={}[]|:;<>?,."
        storage.set(KEY_USER_ID, specialId)
        assertEquals(specialId, storage.get(KEY_USER_ID))
    }

    @Test
    fun `handles unicode characters`() {
        val unicodeId = "用户-123-ユーザー"
        storage.set(KEY_USER_ID, unicodeId)
        assertEquals(unicodeId, storage.get(KEY_USER_ID))
    }
}

package com.metarouter.analytics.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
        // Clear all data before each test
        storage.clearAll()
    }

    @After
    fun teardown() {
        storage.clearAll()
    }

    // Anonymous ID tests

    @Test
    fun `getAnonymousId returns null when not set`() {
        val id = storage.getAnonymousId()
        assertNull(id)
    }

    @Test
    fun `setAnonymousId stores and retrieves value`() {
        val testId = "test-anonymous-id-123"

        val stored = storage.setAnonymousId(testId)
        assertTrue(stored)

        val retrieved = storage.getAnonymousId()
        assertEquals(testId, retrieved)
    }

    @Test
    fun `setAnonymousId rejects empty string`() {
        val stored = storage.setAnonymousId("")
        assertFalse(stored)

        val retrieved = storage.getAnonymousId()
        assertNull(retrieved)
    }

    @Test
    fun `setAnonymousId rejects blank string`() {
        val stored = storage.setAnonymousId("   ")
        assertFalse(stored)

        val retrieved = storage.getAnonymousId()
        assertNull(retrieved)
    }

    @Test
    fun `setAnonymousId overwrites previous value`() {
        storage.setAnonymousId("first-id")
        storage.setAnonymousId("second-id")

        val retrieved = storage.getAnonymousId()
        assertEquals("second-id", retrieved)
    }

    // User ID tests

    @Test
    fun `getUserId returns null when not set`() {
        val id = storage.getUserId()
        assertNull(id)
    }

    @Test
    fun `setUserId stores and retrieves value`() {
        val testId = "user-123"

        val stored = storage.setUserId(testId)
        assertTrue(stored)

        val retrieved = storage.getUserId()
        assertEquals(testId, retrieved)
    }

    @Test
    fun `setUserId rejects empty string`() {
        val stored = storage.setUserId("")
        assertFalse(stored)

        val retrieved = storage.getUserId()
        assertNull(retrieved)
    }

    @Test
    fun `setUserId rejects blank string`() {
        val stored = storage.setUserId("   ")
        assertFalse(stored)

        val retrieved = storage.getUserId()
        assertNull(retrieved)
    }

    @Test
    fun `clearUserId removes value`() {
        storage.setUserId("user-123")

        val cleared = storage.clearUserId()
        assertTrue(cleared)

        val retrieved = storage.getUserId()
        assertNull(retrieved)
    }

    @Test
    fun `clearUserId is idempotent`() {
        storage.setUserId("user-123")
        storage.clearUserId()

        // Clear again - should still return true
        val cleared = storage.clearUserId()
        assertTrue(cleared)
    }

    // Group ID tests

    @Test
    fun `getGroupId returns null when not set`() {
        val id = storage.getGroupId()
        assertNull(id)
    }

    @Test
    fun `setGroupId stores and retrieves value`() {
        val testId = "company-456"

        val stored = storage.setGroupId(testId)
        assertTrue(stored)

        val retrieved = storage.getGroupId()
        assertEquals(testId, retrieved)
    }

    @Test
    fun `setGroupId rejects empty string`() {
        val stored = storage.setGroupId("")
        assertFalse(stored)

        val retrieved = storage.getGroupId()
        assertNull(retrieved)
    }

    @Test
    fun `setGroupId rejects blank string`() {
        val stored = storage.setGroupId("   ")
        assertFalse(stored)

        val retrieved = storage.getGroupId()
        assertNull(retrieved)
    }

    @Test
    fun `clearGroupId removes value`() {
        storage.setGroupId("company-456")

        val cleared = storage.clearGroupId()
        assertTrue(cleared)

        val retrieved = storage.getGroupId()
        assertNull(retrieved)
    }

    @Test
    fun `clearGroupId is idempotent`() {
        storage.setGroupId("company-456")
        storage.clearGroupId()

        // Clear again - should still return true
        val cleared = storage.clearGroupId()
        assertTrue(cleared)
    }

    // ClearAll tests

    @Test
    fun `clearAll removes all identity data`() {
        // Set all IDs
        storage.setAnonymousId("anon-123")
        storage.setUserId("user-123")
        storage.setGroupId("company-456")

        // Clear all
        val cleared = storage.clearAll()
        assertTrue(cleared)

        // Verify all are null
        assertNull(storage.getAnonymousId())
        assertNull(storage.getUserId())
        assertNull(storage.getGroupId())
    }

    @Test
    fun `clearAll is idempotent`() {
        storage.setUserId("user-123")
        storage.clearAll()

        // Clear again - should still return true
        val cleared = storage.clearAll()
        assertTrue(cleared)
    }

    // Persistence tests

    @Test
    fun `data persists across IdentityStorage instances`() {
        val storage1 = IdentityStorage(context)
        storage1.setAnonymousId("anon-persistent")
        storage1.setUserId("user-persistent")
        storage1.setGroupId("group-persistent")

        // Create new instance
        val storage2 = IdentityStorage(context)

        assertEquals("anon-persistent", storage2.getAnonymousId())
        assertEquals("user-persistent", storage2.getUserId())
        assertEquals("group-persistent", storage2.getGroupId())
    }

    // Storage key tests

    @Test
    fun `storage keys match spec`() {
        assertEquals("metarouter:anonymous_id", IdentityStorage.KEY_ANONYMOUS_ID)
        assertEquals("metarouter:user_id", IdentityStorage.KEY_USER_ID)
        assertEquals("metarouter:group_id", IdentityStorage.KEY_GROUP_ID)
    }

    // Edge cases

    @Test
    fun `handles UUID format anonymous ID`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"

        val stored = storage.setAnonymousId(uuid)
        assertTrue(stored)

        val retrieved = storage.getAnonymousId()
        assertEquals(uuid, retrieved)
    }

    @Test
    fun `handles fallback format anonymous ID`() {
        val fallbackId = "fallback-1234567890-abcdefgh"

        val stored = storage.setAnonymousId(fallbackId)
        assertTrue(stored)

        val retrieved = storage.getAnonymousId()
        assertEquals(fallbackId, retrieved)
    }

    @Test
    fun `handles long string IDs`() {
        val longId = "a".repeat(1000)

        storage.setUserId(longId)
        assertEquals(longId, storage.getUserId())
    }

    @Test
    fun `handles special characters in IDs`() {
        val specialId = "user-123!@#$%^&*()_+-={}[]|:;<>?,."

        storage.setUserId(specialId)
        assertEquals(specialId, storage.getUserId())
    }

    @Test
    fun `handles unicode characters in IDs`() {
        val unicodeId = "用户-123-ユーザー"

        storage.setUserId(unicodeId)
        assertEquals(unicodeId, storage.getUserId())
    }
}

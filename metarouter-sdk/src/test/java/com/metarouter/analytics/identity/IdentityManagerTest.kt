package com.metarouter.analytics.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metarouter.analytics.storage.IdentityStorage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for IdentityManager class.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityManagerTest {

    private lateinit var manager: IdentityManager
    private lateinit var storage: IdentityStorage
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = IdentityStorage(context)
        storage.clearAll()
        manager = IdentityManager(context)
    }

    @After
    fun teardown() = runTest {
        manager.reset()
    }

    // Anonymous ID tests

    @Test
    fun `getAnonymousId generates new ID when not set`() = runTest {
        val id = manager.getAnonymousId()

        assertNotNull(id)
        assertTrue(id.isNotEmpty())
    }

    @Test
    fun `getAnonymousId returns same ID on multiple calls`() = runTest {
        val id1 = manager.getAnonymousId()
        val id2 = manager.getAnonymousId()

        assertEquals(id1, id2)
    }

    @Test
    fun `getAnonymousId generates UUID format by default`() = runTest {
        val id = manager.getAnonymousId()

        // UUID format: 8-4-4-4-12 hex characters
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        assertTrue("Expected UUID format, got: $id", id.matches(uuidRegex))
    }

    @Test
    fun `getAnonymousId persists across IdentityManager instances`() = runTest {
        val manager1 = IdentityManager(context)
        val id1 = manager1.getAnonymousId()

        val manager2 = IdentityManager(context)
        val id2 = manager2.getAnonymousId()

        assertEquals(id1, id2)
    }

    @Test
    fun `getAnonymousId loads from storage if present`() = runTest {
        // Manually set in storage
        storage.setAnonymousId("stored-anonymous-id")

        val manager = IdentityManager(context)
        val id = manager.getAnonymousId()

        assertEquals("stored-anonymous-id", id)
    }

    // User ID tests

    @Test
    fun `getUserId returns null when not set`() = runTest {
        val id = manager.getUserId()
        assertNull(id)
    }

    @Test
    fun `setUserId stores and retrieves value`() = runTest {
        val stored = manager.setUserId("user-123")
        assertTrue(stored)

        val retrieved = manager.getUserId()
        assertEquals("user-123", retrieved)
    }

    @Test
    fun `setUserId rejects empty string`() = runTest {
        val stored = manager.setUserId("")
        assertFalse(stored)

        val retrieved = manager.getUserId()
        assertNull(retrieved)
    }

    @Test
    fun `setUserId rejects blank string`() = runTest {
        val stored = manager.setUserId("   ")
        assertFalse(stored)

        val retrieved = manager.getUserId()
        assertNull(retrieved)
    }

    @Test
    fun `setUserId overwrites previous value`() = runTest {
        manager.setUserId("user-1")
        manager.setUserId("user-2")

        val retrieved = manager.getUserId()
        assertEquals("user-2", retrieved)
    }

    @Test
    fun `clearUserId removes value`() = runTest {
        manager.setUserId("user-123")

        val cleared = manager.clearUserId()
        assertTrue(cleared)

        val retrieved = manager.getUserId()
        assertNull(retrieved)
    }

    @Test
    fun `getUserId persists across IdentityManager instances`() = runTest {
        val manager1 = IdentityManager(context)
        manager1.setUserId("user-persistent")

        val manager2 = IdentityManager(context)
        val retrieved = manager2.getUserId()

        assertEquals("user-persistent", retrieved)
    }

    // Group ID tests

    @Test
    fun `getGroupId returns null when not set`() = runTest {
        val id = manager.getGroupId()
        assertNull(id)
    }

    @Test
    fun `setGroupId stores and retrieves value`() = runTest {
        val stored = manager.setGroupId("company-456")
        assertTrue(stored)

        val retrieved = manager.getGroupId()
        assertEquals("company-456", retrieved)
    }

    @Test
    fun `setGroupId rejects empty string`() = runTest {
        val stored = manager.setGroupId("")
        assertFalse(stored)

        val retrieved = manager.getGroupId()
        assertNull(retrieved)
    }

    @Test
    fun `setGroupId rejects blank string`() = runTest {
        val stored = manager.setGroupId("   ")
        assertFalse(stored)

        val retrieved = manager.getGroupId()
        assertNull(retrieved)
    }

    @Test
    fun `clearGroupId removes value`() = runTest {
        manager.setGroupId("company-456")

        val cleared = manager.clearGroupId()
        assertTrue(cleared)

        val retrieved = manager.getGroupId()
        assertNull(retrieved)
    }

    @Test
    fun `getGroupId persists across IdentityManager instances`() = runTest {
        val manager1 = IdentityManager(context)
        manager1.setGroupId("company-persistent")

        val manager2 = IdentityManager(context)
        val retrieved = manager2.getGroupId()

        assertEquals("company-persistent", retrieved)
    }

    // Advertising ID tests

    @Test
    fun `getAdvertisingId returns null when not set`() = runTest {
        val id = manager.getAdvertisingId()
        assertNull(id)
    }

    @Test
    fun `setAdvertisingId stores and retrieves value`() = runTest {
        val adId = "12345678-1234-1234-1234-123456789abc"

        val stored = manager.setAdvertisingId(adId)
        assertTrue(stored)

        val retrieved = manager.getAdvertisingId()
        assertEquals(adId, retrieved)
    }

    @Test
    fun `setAdvertisingId rejects empty string`() = runTest {
        val stored = manager.setAdvertisingId("")
        assertFalse(stored)

        val retrieved = manager.getAdvertisingId()
        assertNull(retrieved)
    }

    @Test
    fun `setAdvertisingId rejects blank string`() = runTest {
        val stored = manager.setAdvertisingId("   ")
        assertFalse(stored)

        val retrieved = manager.getAdvertisingId()
        assertNull(retrieved)
    }

    @Test
    fun `clearAdvertisingId removes value`() = runTest {
        manager.setAdvertisingId("ad-id-123")

        val cleared = manager.clearAdvertisingId()
        assertTrue(cleared)

        val retrieved = manager.getAdvertisingId()
        assertNull(retrieved)
    }

    @Test
    fun `getAdvertisingId persists across IdentityManager instances`() = runTest {
        val manager1 = IdentityManager(context)
        manager1.setAdvertisingId("ad-persistent")

        val manager2 = IdentityManager(context)
        val retrieved = manager2.getAdvertisingId()

        assertEquals("ad-persistent", retrieved)
    }

    // Reset tests

    @Test
    fun `reset clears all identity data`() = runTest {
        // Set all IDs
        manager.getAnonymousId() // generates one
        manager.setUserId("user-123")
        manager.setGroupId("company-456")
        manager.setAdvertisingId("ad-id-789")

        // Reset
        manager.reset()

        // Anonymous ID should be null (will regenerate on next get)
        // Check storage directly to verify it's cleared
        assertNull(storage.getAnonymousId())
        assertNull(storage.getUserId())
        assertNull(storage.getGroupId())
        assertNull(storage.getAdvertisingId())
    }

    @Test
    fun `reset generates new anonymous ID on next getAnonymousId`() = runTest {
        val id1 = manager.getAnonymousId()

        manager.reset()

        val id2 = manager.getAnonymousId()

        assertNotEquals(id1, id2)
    }

    @Test
    fun `reset clears cached values`() = runTest {
        manager.setUserId("user-123")
        manager.setGroupId("company-456")

        manager.reset()

        assertNull(manager.getUserId())
        assertNull(manager.getGroupId())
    }

    // Caching tests

    @Test
    fun `values are cached after first access`() = runTest {
        // Set values
        manager.setUserId("user-123")
        manager.setGroupId("company-456")

        // Get values (should be cached now)
        manager.getUserId()
        manager.getGroupId()

        // Clear storage directly (bypass cache)
        storage.clearAll()

        // Should still return cached values
        assertEquals("user-123", manager.getUserId())
        assertEquals("company-456", manager.getGroupId())
    }

    @Test
    fun `anonymous ID is cached after generation`() = runTest {
        val id1 = manager.getAnonymousId()

        // Clear storage directly (bypass cache)
        storage.clearAll()

        // Should still return cached value
        val id2 = manager.getAnonymousId()
        assertEquals(id1, id2)
    }

    // Edge cases

    @Test
    fun `handles rapid successive calls`() = runTest {
        repeat(100) {
            manager.setUserId("user-$it")
        }

        val finalUserId = manager.getUserId()
        assertEquals("user-99", finalUserId)
    }

    @Test
    fun `handles special characters in IDs`() = runTest {
        val specialId = "user-123!@#$%^&*()_+-={}[]|:;<>?,."

        manager.setUserId(specialId)
        assertEquals(specialId, manager.getUserId())
    }

    @Test
    fun `handles unicode characters in IDs`() = runTest {
        val unicodeId = "用户-123-ユーザー"

        manager.setUserId(unicodeId)
        assertEquals(unicodeId, manager.getUserId())
    }

    @Test
    fun `handles long string IDs`() = runTest {
        val longId = "a".repeat(1000)

        manager.setUserId(longId)
        assertEquals(longId, manager.getUserId())
    }

    // Multiple IDs can coexist

    @Test
    fun `all identity types can be set simultaneously`() = runTest {
        val anonymousId = manager.getAnonymousId()
        manager.setUserId("user-123")
        manager.setGroupId("company-456")
        manager.setAdvertisingId("ad-id-789")

        assertNotNull(manager.getAnonymousId())
        assertEquals("user-123", manager.getUserId())
        assertEquals("company-456", manager.getGroupId())
        assertEquals("ad-id-789", manager.getAdvertisingId())
    }

    @Test
    fun `setting one ID does not affect others`() = runTest {
        manager.getAnonymousId()
        manager.setUserId("user-123")
        manager.setGroupId("company-456")

        val anonymousId = manager.getAnonymousId()

        // Update user ID
        manager.setUserId("user-456")

        // Others should remain unchanged
        assertEquals(anonymousId, manager.getAnonymousId())
        assertEquals("company-456", manager.getGroupId())
    }
}

package com.metarouter.analytics.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Concurrency and thread-safety tests for IdentityManager.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityManagerConcurrencyTest {

    private lateinit var manager: IdentityManager
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = IdentityManager(context)
    }

    @After
    fun teardown() = runTest {
        manager.reset()
    }

    @Test
    fun `concurrent getAnonymousId calls return same ID`() = runTest {
        val results = (1..100).map {
            async {
                manager.getAnonymousId()
            }
        }.awaitAll()

        // All results should be the same
        val uniqueIds = results.toSet()
        assertEquals(1, uniqueIds.size)
    }

    @Test
    fun `concurrent setUserId calls are all applied`() = runTest {
        // Launch 100 concurrent setUserId calls
        (1..100).map { i ->
            async {
                manager.setUserId("user-$i")
            }
        }.awaitAll()

        // Should have one of the user IDs (last one wins)
        val finalUserId = manager.getUserId()
        assertNotNull(finalUserId)
        assertTrue(finalUserId!!.startsWith("user-"))
    }

    @Test
    fun `concurrent get and set operations are safe`() = runTest {
        val getters = (1..50).map {
            async {
                manager.getUserId()
            }
        }

        val setters = (1..50).map { i ->
            async {
                manager.setUserId("user-$i")
            }
        }

        // All operations should complete without exception
        getters.awaitAll()
        setters.awaitAll()

        // Final state should be consistent
        val finalUserId = manager.getUserId()
        assertNotNull(finalUserId)
    }

    @Test
    fun `concurrent operations on different IDs are independent`() = runTest {
        val operations = listOf(
            async { manager.getAnonymousId() },
            async { manager.setUserId("user-1") },
            async { manager.setGroupId("group-1") },
            async { manager.getUserId() },
            async { manager.getGroupId() }
        )

        // All operations should complete without exception
        operations.awaitAll()

        // All IDs should be set
        assertNotNull(manager.getAnonymousId())
        assertNotNull(manager.getUserId())
        assertNotNull(manager.getGroupId())
    }

    @Test
    fun `concurrent clear operations are safe`() = runTest {
        manager.setUserId("user-123")
        manager.setGroupId("group-456")

        val clearers = (1..50).map {
            async {
                manager.clearUserId()
                manager.clearGroupId()
            }
        }

        clearers.awaitAll()

        // All should be cleared
        assertNull(manager.getUserId())
        assertNull(manager.getGroupId())
    }

    @Test
    fun `concurrent reset operations are safe`() = runTest {
        manager.getAnonymousId()
        manager.setUserId("user-123")
        manager.setGroupId("group-456")

        val resetters = (1..10).map {
            async {
                manager.reset()
            }
        }

        resetters.awaitAll()

        // After reset, IDs should be cleared
        assertNull(manager.getUserId())
        assertNull(manager.getGroupId())
    }

    @Test
    fun `high concurrency stress test`() = runTest {
        val operations = (1..1000).map { i ->
            async {
                when (i % 4) {
                    0 -> manager.getAnonymousId()
                    1 -> manager.setUserId("user-$i")
                    2 -> manager.setGroupId("group-$i")
                    3 -> manager.getUserId()
                }
            }
        }

        // All operations should complete without exception
        operations.awaitAll()

        // Final state should be consistent
        assertNotNull(manager.getAnonymousId())
    }

    @Test
    fun `concurrent read operations do not block each other`() = runTest {
        // Set some initial values
        manager.getAnonymousId()
        manager.setUserId("user-123")
        manager.setGroupId("group-456")

        val startTime = System.currentTimeMillis()

        // Launch many concurrent read operations
        val readers = (1..100).map {
            async {
                manager.getAnonymousId()
                manager.getUserId()
                manager.getGroupId()
            }
        }

        readers.awaitAll()

        val duration = System.currentTimeMillis() - startTime

        // Reads should be very fast (< 1 second for 100 operations)
        // This is a sanity check, not a precise benchmark
        assertTrue("Reads took too long: ${duration}ms", duration < 1000)
    }

    @Test
    fun `alternating read-write operations maintain consistency`() = runTest {
        val operations = (1..100).map { i ->
            async {
                if (i % 2 == 0) {
                    // Write
                    manager.setUserId("user-$i")
                } else {
                    // Read
                    manager.getUserId()
                }
            }
        }

        operations.awaitAll()

        // Final value should be one of the written values
        val finalUserId = manager.getUserId()
        assertNotNull(finalUserId)
        assertTrue(finalUserId!!.startsWith("user-"))
        assertTrue(finalUserId.matches(Regex("user-\\d+")))
    }

    @Test
    fun `concurrent operations across all ID types`() = runTest {
        val operations = (1..200).map { i ->
            async {
                when (i % 6) {
                    0 -> manager.getAnonymousId()
                    1 -> manager.setUserId("user-$i")
                    2 -> manager.getUserId()
                    3 -> manager.setGroupId("group-$i")
                    4 -> manager.getGroupId()
                    5 -> {
                        if (i % 12 == 5) manager.clearUserId()
                        if (i % 12 == 11) manager.clearGroupId()
                    }
                }
            }
        }

        // Should complete without deadlock or corruption
        operations.awaitAll()

        // Anonymous ID should always exist
        assertNotNull(manager.getAnonymousId())
    }

    @Test
    fun `rapid identity changes are handled correctly`() = runTest {
        repeat(100) { i ->
            manager.setUserId("user-$i")
            manager.getUserId()
            if (i % 10 == 0) {
                manager.clearUserId()
            }
        }

        // Should end in a consistent state
        val finalUserId = manager.getUserId()
        // Last non-cleared value should be present
        assertNotNull(finalUserId)
    }
}

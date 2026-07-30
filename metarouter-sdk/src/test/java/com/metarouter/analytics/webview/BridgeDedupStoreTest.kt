package com.metarouter.analytics.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeDedupStoreTest {

    private var now = 1_000_000L
    private fun store(
        maxEntries: Int = BridgeDedupStore.DEFAULT_MAX_ENTRIES,
        ttlMillis: Long = BridgeDedupStore.DEFAULT_TTL_MILLIS
    ) = BridgeDedupStore(maxEntries, ttlMillis) { now }

    @Test
    fun `first sighting is new`() {
        assertTrue(store().markIfNew("m-1"))
    }

    @Test
    fun `second sighting within ttl is a duplicate`() {
        val store = store()

        assertTrue(store.markIfNew("m-1"))
        assertFalse(store.markIfNew("m-1"))
    }

    @Test
    fun `distinct ids do not collide`() {
        val store = store()

        assertTrue(store.markIfNew("m-1"))
        assertTrue(store.markIfNew("m-2"))
        assertFalse(store.markIfNew("m-1"))
        assertFalse(store.markIfNew("m-2"))
    }

    @Test
    fun `sighting after ttl expiry is new again`() {
        val store = store(ttlMillis = 1_000)

        assertTrue(store.markIfNew("m-1"))
        now += 1_001
        assertTrue(store.markIfNew("m-1"))
    }

    @Test
    fun `sighting at exactly the ttl boundary counts as expired`() {
        // The window check is strict (<): elapsed == ttl is outside the window. A
        // flipped comparison would silently change this; one assertion pins it.
        val store = store(ttlMillis = 1_000)

        assertTrue(store.markIfNew("m-1"))
        now += 1_000
        assertTrue(store.markIfNew("m-1"))
    }

    @Test
    fun `duplicate does not refresh the ttl window`() {
        val store = store(ttlMillis = 1_000)

        assertTrue(store.markIfNew("m-1"))
        now += 600
        assertFalse(store.markIfNew("m-1"))
        // 1_100ms after FIRST sighting: if the duplicate at 600ms had refreshed the
        // timestamp, this would still be inside the window and report a duplicate.
        now += 500
        assertTrue(store.markIfNew("m-1"))
    }

    @Test
    fun `oldest entry is evicted when the store is full`() {
        val store = store(maxEntries = 3)

        assertTrue(store.markIfNew("m-1"))
        assertTrue(store.markIfNew("m-2"))
        assertTrue(store.markIfNew("m-3"))
        assertTrue(store.markIfNew("m-4"))

        assertEquals(3, store.size())
        // m-1 was evicted, so it reads as new; m-4 is still live.
        assertTrue(store.markIfNew("m-1"))
        assertFalse(store.markIfNew("m-4"))
    }

    @Test
    fun `size never exceeds the bound`() {
        val store = store(maxEntries = 10)

        repeat(100) { assertTrue(store.markIfNew("m-$it")) }

        assertEquals(10, store.size())
    }

    @Test
    fun `expired entry re-sighting moves it to the back of the eviction order`() {
        val store = store(maxEntries = 2, ttlMillis = 1_000)

        assertTrue(store.markIfNew("m-1"))
        now += 1_001
        assertTrue(store.markIfNew("m-1")) // expired → re-recorded at `now`
        assertTrue(store.markIfNew("m-2"))
        assertTrue(store.markIfNew("m-3")) // store full — evicts the eldest entry

        // m-1 was re-recorded before m-2, so m-1 is the eviction victim; m-2 survives.
        // If the expired re-sighting had kept m-1's original slot position, m-2 would
        // have been evicted here instead.
        assertFalse(store.markIfNew("m-2"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero maxEntries is rejected`() {
        BridgeDedupStore(maxEntries = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero ttl is rejected`() {
        BridgeDedupStore(ttlMillis = 0)
    }
}

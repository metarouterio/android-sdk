package com.metarouter.analytics.consent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentReconcilerTest {

    private val registry = listOf(
        ConsentCategory("c0001", defaultOptOut = false),
        ConsentCategory("c0002"),
        ConsentCategory("c0004")
    )

    @Test
    fun `empty registry passes incoming through`() {
        val incoming = Consent(explicit = true, optOut = mapOf("anything" to true, "goes" to false), timestamp = 7)

        val result = ConsentReconciler.reconcile(incoming, emptyList())

        assertEquals("nothing to reconcile against — dropping keys would lose the host's signal", incoming, result)
    }

    @Test
    fun `full overlap keeps incoming values`() {
        val incoming = Consent(explicit = true, optOut = mapOf("c0001" to true, "c0002" to false, "c0004" to false))

        val result = ConsentReconciler.reconcile(incoming, registry)

        assertEquals(mapOf("c0001" to true, "c0002" to false, "c0004" to false), result.optOut)
    }

    @Test
    fun `partial overlap fills missing with registered default`() {
        val incoming = Consent(explicit = true, optOut = mapOf("c0002" to false))

        val result = ConsentReconciler.reconcile(incoming, registry)

        assertEquals(mapOf("c0001" to false, "c0002" to false, "c0004" to true), result.optOut)
    }

    @Test
    fun `unknown keys are dropped`() {
        val incoming = Consent(
            explicit = true,
            optOut = mapOf("c0001" to false, "c0002" to false, "c0004" to false, "c9999" to false)
        )

        val result = ConsentReconciler.reconcile(incoming, registry)

        assertNull(result.optOut["c9999"])
        assertEquals(3, result.optOut.size)
    }

    @Test
    fun `explicit and timestamp pass through unchanged`() {
        val explicitIn = Consent(explicit = true, optOut = emptyMap(), timestamp = 42)
        val implicitIn = Consent(explicit = false, optOut = emptyMap(), timestamp = null)

        assertTrue(ConsentReconciler.reconcile(explicitIn, registry).explicit)
        assertEquals(42L, ConsentReconciler.reconcile(explicitIn, registry).timestamp)
        assertFalse(ConsentReconciler.reconcile(implicitIn, registry).explicit)
        assertNull(ConsentReconciler.reconcile(implicitIn, registry).timestamp)
    }

    /**
     * Incoming keys are canonicalized before matching. A host building `Consent`
     * directly with OneTrust's native casing must not fall through to defaults — for a
     * category whose default is opt-IN, that silently drops an opt-out.
     */
    @Test
    fun `incoming keys are canonicalized before matching`() {
        val incoming = Consent(explicit = true, optOut = mapOf("C0001" to true, " C0002 " to false))

        val result = ConsentReconciler.reconcile(incoming, registry)

        assertEquals("the opt-out survives; default would have been false", true, result.optOut["c0001"])
        assertEquals(false, result.optOut["c0002"])
        assertNull(result.optOut["C0001"])
    }

    /** A collision must resolve the same way every run — and the safe way — in either order. */
    @Test
    fun `keys colliding after canonicalization resolve opted out`() {
        val upperFirst = Consent(explicit = true, optOut = linkedMapOf("C0001" to false, "c0001" to true))
        val lowerFirst = Consent(explicit = true, optOut = linkedMapOf("c0001" to true, "C0001" to false))

        assertEquals(true, ConsentReconciler.reconcile(upperFirst, registry).optOut["c0001"])
        assertEquals(true, ConsentReconciler.reconcile(lowerFirst, registry).optOut["c0001"])
    }

    @Test
    fun `empty registry pass-through is canonicalized`() {
        val incoming = Consent(explicit = true, optOut = mapOf("C0004" to true, "  " to false))

        val result = ConsentReconciler.reconcile(incoming, emptyList())

        assertEquals("lowercase keys, blank dropped", mapOf("c0004" to true), result.optOut)
    }

    @Test
    fun `implicit default is explicit false with registered defaults`() {
        val result = ConsentReconciler.implicitDefault(registry)

        assertFalse(result.explicit)
        assertEquals(mapOf("c0001" to false, "c0002" to true, "c0004" to true), result.optOut)
        assertNull(result.timestamp)
    }

    @Test
    fun `implicit default for empty registry is empty map`() {
        val result = ConsentReconciler.implicitDefault(emptyList())

        assertFalse(result.explicit)
        assertEquals(emptyMap<String, Boolean>(), result.optOut)
    }

    /**
     * Reconciliation is a projection onto the registry: applying it twice yields the
     * same block, which is what lets the manager re-reconcile stored state on every
     * registration without drift.
     */
    @Test
    fun `reconcile is idempotent`() {
        val incoming = Consent(explicit = true, optOut = mapOf("c0002" to false, "junk" to true), timestamp = 1)

        val once = ConsentReconciler.reconcile(incoming, registry)
        val twice = ConsentReconciler.reconcile(once, registry)

        assertEquals(once, twice)
    }
}

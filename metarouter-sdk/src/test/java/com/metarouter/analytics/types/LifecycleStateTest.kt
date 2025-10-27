package com.metarouter.analytics.types

import org.junit.Assert.*
import org.junit.Test

class LifecycleStateTest {

    @Test
    fun `toString returns lowercase string`() {
        assertEquals("idle", LifecycleState.IDLE.toString())
        assertEquals("initializing", LifecycleState.INITIALIZING.toString())
        assertEquals("ready", LifecycleState.READY.toString())
        assertEquals("resetting", LifecycleState.RESETTING.toString())
        assertEquals("disabled", LifecycleState.DISABLED.toString())
    }

    @Test
    fun `all lifecycle states are present`() {
        val allStates = LifecycleState.values()
        assertEquals(5, allStates.size)
        assertTrue(allStates.contains(LifecycleState.IDLE))
        assertTrue(allStates.contains(LifecycleState.INITIALIZING))
        assertTrue(allStates.contains(LifecycleState.READY))
        assertTrue(allStates.contains(LifecycleState.RESETTING))
        assertTrue(allStates.contains(LifecycleState.DISABLED))
    }

    @Test
    fun `lifecycle states have correct order`() {
        val states = LifecycleState.values()
        assertEquals(LifecycleState.IDLE, states[0])
        assertEquals(LifecycleState.INITIALIZING, states[1])
        assertEquals(LifecycleState.READY, states[2])
        assertEquals(LifecycleState.RESETTING, states[3])
        assertEquals(LifecycleState.DISABLED, states[4])
    }
}

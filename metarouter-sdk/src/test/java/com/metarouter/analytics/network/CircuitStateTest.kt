package com.metarouter.analytics.network

import org.junit.Test
import org.junit.Assert.*

class CircuitStateTest {

    @Test
    fun `CircuitState has three states`() {
        val states = listOf(
            CircuitState.Closed,
            CircuitState.Open,
            CircuitState.HalfOpen
        )
        assertEquals(3, states.size)
    }

    @Test
    fun `Closed is singleton`() {
        assertSame(CircuitState.Closed, CircuitState.Closed)
    }

    @Test
    fun `Open is singleton`() {
        assertSame(CircuitState.Open, CircuitState.Open)
    }

    @Test
    fun `HalfOpen is singleton`() {
        assertSame(CircuitState.HalfOpen, CircuitState.HalfOpen)
    }

    @Test
    fun `states are distinguishable`() {
        assertNotEquals(CircuitState.Closed, CircuitState.Open)
        assertNotEquals(CircuitState.Open, CircuitState.HalfOpen)
        assertNotEquals(CircuitState.Closed, CircuitState.HalfOpen)
    }
}

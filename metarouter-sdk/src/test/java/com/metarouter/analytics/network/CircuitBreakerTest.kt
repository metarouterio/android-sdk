package com.metarouter.analytics.network

import org.junit.Test
import org.junit.Assert.*

class CircuitBreakerTest {

    // Initial State

    @Test
    fun `starts in closed state`() {
        val breaker = CircuitBreaker()
        assertEquals(CircuitState.Closed, breaker.getState())
    }

    @Test
    fun `beforeRequest returns 0 when closed`() {
        val breaker = CircuitBreaker()
        assertEquals(0L, breaker.beforeRequest())
    }

    // onSuccess behavior

    @Test
    fun `onSuccess resets consecutive failures`() {
        val breaker = CircuitBreaker(failureThreshold = 3)
        breaker.onFailure()
        breaker.onFailure()
        breaker.onSuccess()
        // Should not trip after 2 more failures (total 2, not 4)
        breaker.onFailure()
        breaker.onFailure()
        assertEquals(CircuitState.Closed, breaker.getState())
    }

    // onNonRetryable behavior

    @Test
    fun `onNonRetryable resets failures without state change`() {
        val breaker = CircuitBreaker(failureThreshold = 3)
        breaker.onFailure()
        breaker.onFailure()
        breaker.onNonRetryable()
        // Failures reset, should need 3 more to trip
        breaker.onFailure()
        breaker.onFailure()
        assertEquals(CircuitState.Closed, breaker.getState())
    }

    // Threshold behavior

    @Test
    fun `opens after reaching failure threshold`() {
        val breaker = CircuitBreaker(failureThreshold = 3)
        breaker.onFailure()
        breaker.onFailure()
        assertEquals(CircuitState.Closed, breaker.getState())
        breaker.onFailure()
        assertEquals(CircuitState.Open, breaker.getState())
    }

    @Test
    fun `beforeRequest returns delay when open`() {
        val breaker = CircuitBreaker(failureThreshold = 1, baseCooldownMs = 10_000)
        breaker.onFailure()
        val delay = breaker.beforeRequest()
        assertTrue("Expected delay > 0, got $delay", delay > 0)
        assertTrue("Expected delay <= 12000 (with jitter), got $delay", delay <= 12_000)
    }

    @Test
    fun `getRemainingCooldownMs returns 0 when closed`() {
        val breaker = CircuitBreaker()
        assertEquals(0L, breaker.getRemainingCooldownMs())
    }

    @Test
    fun `getRemainingCooldownMs returns positive when open`() {
        val breaker = CircuitBreaker(failureThreshold = 1, baseCooldownMs = 10_000)
        breaker.onFailure()
        assertTrue(breaker.getRemainingCooldownMs() > 0)
    }
}

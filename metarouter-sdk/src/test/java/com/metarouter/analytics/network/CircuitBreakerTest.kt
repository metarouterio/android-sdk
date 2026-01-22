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

    // Half-Open behavior

    @Test
    fun `transitions to half-open after cooldown expires`() {
        val breaker = CircuitBreaker(
            failureThreshold = 1,
            baseCooldownMs = 50,  // Short for testing
            jitterRatio = 0.0     // No jitter for predictable timing
        )
        breaker.onFailure()
        assertEquals(CircuitState.Open, breaker.getState())

        // Wait for cooldown
        Thread.sleep(60)

        // beforeRequest should transition to half-open
        assertEquals(0L, breaker.beforeRequest())
        assertEquals(CircuitState.HalfOpen, breaker.getState())
    }

    @Test
    fun `closes on success in half-open state`() {
        val breaker = CircuitBreaker(
            failureThreshold = 1,
            baseCooldownMs = 50,
            jitterRatio = 0.0
        )
        breaker.onFailure()
        Thread.sleep(60)
        breaker.beforeRequest() // Transition to half-open

        breaker.onSuccess()
        assertEquals(CircuitState.Closed, breaker.getState())
    }

    @Test
    fun `reopens on failure in half-open state`() {
        val breaker = CircuitBreaker(
            failureThreshold = 1,
            baseCooldownMs = 50,
            jitterRatio = 0.0
        )
        breaker.onFailure()
        Thread.sleep(60)
        breaker.beforeRequest() // Transition to half-open

        breaker.onFailure()
        assertEquals(CircuitState.Open, breaker.getState())
    }

    @Test
    fun `half-open limits concurrent requests`() {
        val breaker = CircuitBreaker(
            failureThreshold = 1,
            baseCooldownMs = 50,
            halfOpenMaxConcurrent = 1,
            jitterRatio = 0.0
        )
        breaker.onFailure()
        Thread.sleep(60)

        // First request transitions Open->HalfOpen and is allowed
        assertEquals(0L, breaker.beforeRequest())
        assertEquals(CircuitState.HalfOpen, breaker.getState())

        // Second request is the first "in-flight" request, allowed
        assertEquals(0L, breaker.beforeRequest())

        // Third request exceeds halfOpenMaxConcurrent, should return delay
        val delay = breaker.beforeRequest()
        assertEquals(200L, delay)
    }

    // Backoff behavior

    @Test
    fun `backoff increases exponentially on repeated opens`() {
        val breaker = CircuitBreaker(
            failureThreshold = 1,
            baseCooldownMs = 1000,
            maxCooldownMs = 120_000,
            jitterRatio = 0.0
        )

        // First open: ~1000ms
        breaker.onFailure()
        val firstCooldown = breaker.getRemainingCooldownMs()
        assertTrue("First cooldown should be ~1000ms, got $firstCooldown", firstCooldown in 900..1100)

        // Wait and recover
        Thread.sleep(1050)
        breaker.beforeRequest()
        breaker.onSuccess()

        // Second open: ~2000ms
        breaker.onFailure()
        val secondCooldown = breaker.getRemainingCooldownMs()
        assertTrue("Second cooldown should be ~2000ms, got $secondCooldown", secondCooldown in 1900..2100)
    }

    @Test
    fun `backoff respects max cooldown`() {
        val breaker = CircuitBreaker(
            failureThreshold = 1,
            baseCooldownMs = 50_000,
            maxCooldownMs = 60_000,
            jitterRatio = 0.0
        )

        // Trip multiple times to exceed max
        repeat(5) {
            breaker.onFailure()
            // Manually reset for next iteration (simulating recovery)
            breaker.onSuccess()
        }
        breaker.onFailure()

        val cooldown = breaker.getRemainingCooldownMs()
        assertTrue("Cooldown should not exceed max (60000), got $cooldown", cooldown <= 60_000)
    }

    @Test
    fun `jitter adds randomness to backoff`() {
        val cooldowns = mutableSetOf<Long>()

        repeat(10) {
            val breaker = CircuitBreaker(
                failureThreshold = 1,
                baseCooldownMs = 10_000,
                jitterRatio = 0.2
            )
            breaker.onFailure()
            cooldowns.add(breaker.getRemainingCooldownMs())
        }

        // With 20% jitter on 10000ms, we should see variation
        // Range should be 8000-12000ms, so multiple different values expected
        assertTrue("Expected variation in cooldowns with jitter, got $cooldowns", cooldowns.size > 1)
    }

    // Edge cases

    @Test
    fun `failureThreshold of 1 trips immediately`() {
        val breaker = CircuitBreaker(failureThreshold = 1)
        breaker.onFailure()
        assertEquals(CircuitState.Open, breaker.getState())
    }

    @Test
    fun `zero jitter produces consistent backoff`() {
        val breaker1 = CircuitBreaker(failureThreshold = 1, baseCooldownMs = 5000, jitterRatio = 0.0)
        val breaker2 = CircuitBreaker(failureThreshold = 1, baseCooldownMs = 5000, jitterRatio = 0.0)

        breaker1.onFailure()
        breaker2.onFailure()

        val diff = kotlin.math.abs(breaker1.getRemainingCooldownMs() - breaker2.getRemainingCooldownMs())
        assertTrue("Zero jitter should produce consistent backoff, diff was $diff", diff < 100)
    }
}

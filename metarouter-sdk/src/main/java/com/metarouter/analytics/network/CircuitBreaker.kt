package com.metarouter.analytics.network

/**
 * Circuit breaker that prevents cascading failures by tracking request success/failure.
 *
 * **States:**
 * - [CircuitState.Closed]: All requests allowed, tracking consecutive failures.
 * - [CircuitState.Open]: Requests blocked for a cooldown period after hitting failure threshold.
 * - [CircuitState.HalfOpen]: After cooldown, limited concurrent "probe" requests allowed to test recovery.
 *
 * **Behavior:**
 * - Call [beforeRequest] to check if a request is allowed; returns delay in ms (0 = proceed immediately).
 * - Call [onSuccess] after a successful request to reset failure count and close the circuit.
 * - Call [onFailure] after a retryable failure (5xx, 408, 429) to increment failure count.
 * - Call [onNonRetryable] after non-retryable errors (4xx except 408/429) to reset failure count without state change.
 *
 * **Backoff:**
 * - Exponential: cooldown doubles each time the circuit opens, up to [maxCooldownMs].
 * - Jitter: randomizes delay by ±[jitterRatio] to avoid thundering herd.
 *
 * **Thread safety:** Uses synchronized for thread safety.
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val baseCooldownMs: Long = 10_000L,
    private val maxCooldownMs: Long = 120_000L,
    private val jitterRatio: Double = 0.2,
    private val halfOpenMaxConcurrent: Int = 1
) {
    private var state: CircuitState = CircuitState.Closed
    private var consecutiveFailures = 0
    private var openCount = 0
    private var openUntil: Long = 0L
    private var halfOpenInFlight = 0
    private val lock = Any()

    /**
     * Called after a successful request. Resets failure count and closes the circuit.
     */
    fun onSuccess() {
        synchronized(lock) {
            consecutiveFailures = 0
            if (state != CircuitState.Closed) {
                state = CircuitState.Closed
                halfOpenInFlight = 0
            }
        }
    }

    /**
     * Called after a retryable failure (5xx, 408, 429).
     * Increments failure count and trips circuit if threshold reached.
     */
    fun onFailure() {
        synchronized(lock) {
            consecutiveFailures++
            if (state == CircuitState.Closed && consecutiveFailures >= failureThreshold) {
                tripOpen()
            } else if (state == CircuitState.HalfOpen) {
                tripOpen()
            }
        }
    }

    /**
     * Called after a non-retryable error (4xx except 408/429).
     * Resets failure count without changing state.
     */
    fun onNonRetryable() {
        synchronized(lock) {
            consecutiveFailures = 0
        }
    }

    /**
     * Check if a request is allowed.
     * @return delay in milliseconds to wait before proceeding (0 = proceed immediately)
     */
    fun beforeRequest(): Long {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            return when (state) {
                CircuitState.Closed -> 0L
                CircuitState.Open -> {
                    if (now >= openUntil) {
                        state = CircuitState.HalfOpen
                        halfOpenInFlight = 0
                        0L
                    } else {
                        maxOf(0L, openUntil - now)
                    }
                }
                CircuitState.HalfOpen -> {
                    if (halfOpenInFlight >= halfOpenMaxConcurrent) {
                        200L // Small delay to retry later
                    } else {
                        halfOpenInFlight++
                        0L
                    }
                }
            }
        }
    }

    /**
     * Get the current circuit breaker state.
     */
    fun getState(): CircuitState {
        synchronized(lock) {
            return state
        }
    }

    /**
     * Get remaining cooldown time in milliseconds (0 if not in open state).
     */
    fun getRemainingCooldownMs(): Long {
        synchronized(lock) {
            if (state != CircuitState.Open) return 0L
            val remaining = openUntil - System.currentTimeMillis()
            return maxOf(0L, remaining)
        }
    }

    private fun tripOpen() {
        openCount++
        val delay = calculateBackoffMs()
        openUntil = System.currentTimeMillis() + delay
        state = CircuitState.Open
        consecutiveFailures = 0
        halfOpenInFlight = 0
    }

    private fun calculateBackoffMs(): Long {
        val exponent = maxOf(0, openCount - 1)
        val base = minOf(maxCooldownMs, baseCooldownMs * (1L shl exponent))
        val jitter = (base * jitterRatio).toLong()
        val delta = if (jitter == 0L) 0L else (-jitter..jitter).random()
        return maxOf(0L, base + delta)
    }
}

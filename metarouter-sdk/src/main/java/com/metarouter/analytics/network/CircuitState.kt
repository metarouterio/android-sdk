package com.metarouter.analytics.network

/**
 * Represents the state of a circuit breaker.
 *
 * - [Closed]: Normal operation, all requests allowed
 * - [Open]: Circuit tripped, requests blocked for cooldown period
 * - [HalfOpen]: Testing recovery, limited requests allowed
 */
sealed class CircuitState {
    object Closed : CircuitState()
    object Open : CircuitState()
    object HalfOpen : CircuitState()
}

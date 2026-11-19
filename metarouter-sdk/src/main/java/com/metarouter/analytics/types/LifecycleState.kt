package com.metarouter.analytics.types

/**
 * SDK lifecycle states for tracking initialization and operational status.
 *
 * State transitions:
 * - idle → initializing → ready → resetting → idle
 * - initializing/ready → disabled (fatal config error) → resetting (on reset only)
 */
enum class LifecycleState {
    /**
     * Initial state or after reset. SDK is not operational.
     */
    IDLE,

    /**
     * Initialization in progress. SDK is not yet ready to accept events.
     */
    INITIALIZING,

    /**
     * Fully operational. SDK is ready to track events.
     */
    READY,

    /**
     * Reset in progress. Clearing all state and storage.
     */
    RESETTING,

    /**
     * Disabled by a fatal configuration error (401, 403, 404): events are dropped for
     * the session. Only reset() leaves it.
     */
    DISABLED;

    override fun toString(): String = name.lowercase()
}

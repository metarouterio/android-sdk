package com.metarouter.analytics.types

/**
 * SDK lifecycle states for tracking initialization and operational status.
 *
 * State transitions:
 * - idle → initializing → ready → resetting → idle
 * - ready → disabled (terminal state for fatal errors)
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
     * Permanently disabled due to fatal configuration error (e.g., 401, 403).
     * Terminal state - no transitions allowed.
     */
    DISABLED;

    override fun toString(): String = name.lowercase()
}

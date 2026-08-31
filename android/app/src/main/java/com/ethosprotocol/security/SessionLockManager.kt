package com.ethosprotocol.security

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Singleton that tracks user activity and locks the session after a configurable
 * period of inactivity. The lock is evaluated whenever the app returns to the
 * foreground — see [AppLifecycleObserver] for the lifecycle hook.
 *
 * Usage:
 *   - Call [recordActivity] on meaningful user interactions to reset the timer.
 *   - [AppLifecycleObserver] calls [onAppBackground] / [onAppForeground] automatically.
 *   - Collect [isLocked] in your ViewModel / Composable and show a lock screen.
 *   - Call [unlock] after the user re-authenticates (biometric / PIN).
 */
object SessionLockManager {

    /** Inactivity timeout in milliseconds. Default is 5 minutes. */
    var timeoutMs: Long = 5 * 60 * 1_000L

    /**
     * Epoch-millisecond timestamp of the last recorded activity.
     * `internal` so tests can seed it directly without waiting real time.
     */
    internal var lastActivityTime: Long = System.currentTimeMillis()

    /**
     * `true` when the session is locked and the UI should present a
     * re-authentication prompt.
     */
    val isLocked: MutableStateFlow<Boolean> = MutableStateFlow(false)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Records that the user performed an action right now, resetting the
     * inactivity clock. Call on significant user interactions (tapping,
     * submitting forms, etc.) to prevent premature lock-out during active use.
     */
    fun recordActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    /**
     * Called when the app moves to the background. Records the current time so
     * the elapsed interval can be computed when the app returns to the foreground.
     */
    fun onAppBackground() {
        recordActivity()
    }

    /**
     * Called when the app returns to the foreground. If the time elapsed since
     * [lastActivityTime] meets or exceeds [timeoutMs], the session is locked.
     */
    fun onAppForeground() {
        val elapsed = System.currentTimeMillis() - lastActivityTime
        if (elapsed >= timeoutMs) {
            isLocked.value = true
        }
    }

    /**
     * Clears the lock and resets the inactivity clock. Call after the user
     * successfully re-authenticates.
     */
    fun unlock() {
        isLocked.value = false
        recordActivity()
    }
}

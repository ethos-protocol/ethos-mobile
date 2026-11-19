package com.ethosprotocol.security

import android.os.SystemClock
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

    enum class RefreshResult {
        SUCCESS,
        AUTHENTICATION_FAILED,
        TEMPORARY_FAILURE
    }

    /** Inactivity timeout in milliseconds. Default is 5 minutes. */
    var timeoutMs: Long = 5 * 60 * 1_000L

    /**
     * Minimum interval between sliding-window token refreshes, in milliseconds.
     * Default is 5 minutes. Successful requests within this window do not trigger
     * a refresh, avoiding a refresh on every single call.
     */
    var refreshIntervalMs: Long = 5 * 60 * 1_000L

    /**
     * Monotonic millisecond clock used for every inactivity and refresh timer.
     * `SystemClock.elapsedRealtime()` is not affected by wall-clock changes (manual
     * edits or NTP corrections), so the lock cannot fire early or be skipped by
     * changing the device time. Injectable so tests can advance time deterministically.
     */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * Monotonic-clock timestamp of the last recorded activity, or `null` if none has
     * been recorded yet. `internal` so tests can seed it directly.
     */
    internal var lastActivityTime: Long? = null

    /**
     * Monotonic-clock timestamp of the last successful token refresh, or `null` if
     * no refresh has succeeded. `internal` so tests can seed it directly.
     */
    internal var lastRefreshTime: Long? = null

    /**
     * `true` when the session is locked and the UI should present a
     * re-authentication prompt.
     */
    val isLocked: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
        * Invoked when a sliding-window refresh is due. Implementations should
        * perform the actual token refresh and return an explicit result. Only an
        * authentication rejection locks the session; transient failures can retry.
     */
    var refreshHandler: (() -> RefreshResult)? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Records that the user performed an action right now, resetting the
     * inactivity clock. Call on significant user interactions (tapping,
     * submitting forms, etc.) to prevent premature lock-out during active use.
     */
    fun recordActivity() {
        lastActivityTime = clock()
    }

    /**
     * Called after a request completes successfully. Records activity and, when
     * the sliding-window refresh interval has elapsed, attempts to refresh the
     * token to extend the session for active users.
     *
     * @return `true` if a refresh was attempted and succeeded, `false` otherwise.
     */
    fun onRequestSucceeded(): Boolean {
        recordActivity()
        val now = clock()
        val last = lastRefreshTime
        if (last != null && now - last < refreshIntervalMs) {
            return false
        }
        return refreshToken()
    }

    /**
     * Attempts a token refresh via [refreshHandler]. On success the refresh
     * timestamp is advanced, extending the sliding window. Only an explicit
     * authentication failure locks the session; network and other transient
     * failures leave it unlocked for a later retry.
     *
     * @return `true` if the refresh succeeded, `false` otherwise.
     */
    fun refreshToken(): Boolean {
        val handler = refreshHandler ?: return false
        val result = try {
            handler()
        } catch (_: Exception) {
            RefreshResult.TEMPORARY_FAILURE
        }
        when (result) {
            RefreshResult.SUCCESS -> {
                lastRefreshTime = clock()
                recordActivity()
                return true
            }
            RefreshResult.AUTHENTICATION_FAILED -> isLocked.value = true
            RefreshResult.TEMPORARY_FAILURE -> Unit
        }
        return false
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
        // No recorded activity means the process just started; treat that as activity now.
        val last = lastActivityTime ?: return recordActivity()
        if (clock() - last >= timeoutMs) {
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

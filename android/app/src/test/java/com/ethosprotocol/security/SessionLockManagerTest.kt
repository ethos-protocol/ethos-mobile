package com.ethosprotocol.security

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionLockManagerTest {

    @Before
    fun setUp() {
        // Reset to a clean unlocked state with the default timeout before each test.
        SessionLockManager.timeoutMs = 5 * 60 * 1_000L
        SessionLockManager.isLocked.value = false
        SessionLockManager.lastActivityTime = System.currentTimeMillis()
    }

    @After
    fun tearDown() {
        // Restore defaults so other tests are not affected.
        SessionLockManager.isLocked.value = false
        SessionLockManager.timeoutMs = 5 * 60 * 1_000L
        SessionLockManager.lastActivityTime = System.currentTimeMillis()
    }

    // -------------------------------------------------------------------------
    // testNoLockBeforeTimeout
    // -------------------------------------------------------------------------

    /**
     * When the app foregrounds before the timeout has elapsed, the session
     * must NOT be locked.
     */
    @Test
    fun testNoLockBeforeTimeout() {
        // Seed lastActivityTime to 60 s ago with a 300 s (300_000 ms) timeout.
        SessionLockManager.timeoutMs = 300_000L
        SessionLockManager.lastActivityTime = System.currentTimeMillis() - 60_000L

        SessionLockManager.onAppForeground()

        assertFalse(
            "Session should NOT be locked when only 60 s have elapsed with a 300 s timeout",
            SessionLockManager.isLocked.value
        )
    }

    // -------------------------------------------------------------------------
    // testLockAfterTimeout
    // -------------------------------------------------------------------------

    /**
     * When the app foregrounds after the timeout has been exceeded, the session
     * MUST be locked.
     */
    @Test
    fun testLockAfterTimeout() {
        // Seed lastActivityTime to 301 s ago — just over the 300 s (300_000 ms) limit.
        SessionLockManager.timeoutMs = 300_000L
        SessionLockManager.lastActivityTime = System.currentTimeMillis() - 301_000L

        SessionLockManager.onAppForeground()

        assertTrue(
            "Session MUST be locked when 301 s have elapsed with a 300 s timeout",
            SessionLockManager.isLocked.value
        )
    }

    /**
     * Boundary: elapsed time exactly equal to the timeout should also lock.
     */
    @Test
    fun testLockAtExactTimeout() {
        SessionLockManager.timeoutMs = 300_000L
        SessionLockManager.lastActivityTime = System.currentTimeMillis() - 300_000L

        SessionLockManager.onAppForeground()

        assertTrue(
            "Session MUST be locked when elapsed time equals the timeout exactly",
            SessionLockManager.isLocked.value
        )
    }

    // -------------------------------------------------------------------------
    // testUnlockResetsLock
    // -------------------------------------------------------------------------

    /**
     * After [SessionLockManager.unlock] is called, [SessionLockManager.isLocked]
     * must be `false` and the inactivity clock must be reset so that a
     * subsequent immediate foreground check does not re-lock the session.
     */
    @Test
    fun testUnlockResetsLock() {
        // Put the session into a locked state.
        SessionLockManager.timeoutMs = 300_000L
        SessionLockManager.lastActivityTime = System.currentTimeMillis() - 600_000L
        SessionLockManager.onAppForeground()
        assertTrue("Pre-condition: session should be locked", SessionLockManager.isLocked.value)

        SessionLockManager.unlock()

        assertFalse(
            "isLocked should be false immediately after unlock()",
            SessionLockManager.isLocked.value
        )

        // Simulate a near-instant return to foreground (0 ms elapsed after unlock).
        SessionLockManager.onAppForeground()
        assertFalse(
            "Session should NOT re-lock immediately after unlock() resets the clock",
            SessionLockManager.isLocked.value
        )
    }

    // -------------------------------------------------------------------------
    // recordActivity prevents lock
    // -------------------------------------------------------------------------

    /**
     * [SessionLockManager.recordActivity] resets the clock; a foreground check
     * after recording activity should not lock even if the seeded time was stale.
     */
    @Test
    fun testRecordActivityPreventsLock() {
        SessionLockManager.timeoutMs = 300_000L
        SessionLockManager.lastActivityTime = System.currentTimeMillis() - 600_000L

        // User interacts — clock is reset to now.
        SessionLockManager.recordActivity()
        SessionLockManager.onAppForeground()

        assertFalse(
            "Session should NOT lock after recordActivity() refreshes the clock",
            SessionLockManager.isLocked.value
        )
    }

    // -------------------------------------------------------------------------
    // onAppBackground refreshes the clock
    // -------------------------------------------------------------------------

    /**
     * [SessionLockManager.onAppBackground] should refresh [lastActivityTime] so
     * that a brief background trip does not trigger a lock on the next foreground.
     */
    @Test
    fun testOnAppBackgroundRefreshesClock() {
        SessionLockManager.timeoutMs = 300_000L
        // Stale activity time — if onAppBackground didn't reset the clock this
        // foreground check would lock the session.
        SessionLockManager.lastActivityTime = System.currentTimeMillis() - 600_000L

        SessionLockManager.onAppBackground()
        // Return to foreground almost immediately (< 1 ms).
        SessionLockManager.onAppForeground()

        assertFalse(
            "onAppBackground() should reset the clock so a brief background trip doesn't lock",
            SessionLockManager.isLocked.value
        )
    }
}

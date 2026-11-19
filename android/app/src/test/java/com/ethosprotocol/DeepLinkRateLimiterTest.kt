package com.ethosprotocol

import androidx.lifecycle.SavedStateHandle
import com.ethosprotocol.services.DeepLinkRateLimiter
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for [DeepLinkRateLimiter] — client-side rate limiting on deep-link-triggered API calls.
 *
 * Issue #263: Add Rate Limiting on Deep-Link-Triggered API Calls
 *
 * A malicious or malformed deep link opened repeatedly (e.g., via crafted intent) could trigger
 * repeated API calls faster than human re-taps. This enforces a client-side cooldown per vault ID.
 */
class DeepLinkRateLimiterTest {

    private lateinit var savedStateHandle: SavedStateHandle

    /** Fake monotonic clock, advanced explicitly so no test needs real time or Thread.sleep. */
    private var fakeNow = 1_000_000L

    @Before
    fun setUp() {
        savedStateHandle = SavedStateHandle()
        fakeNow = 1_000_000L
        DeepLinkRateLimiter.clock = { fakeNow }
    }

    // =========================================================================
    // Happy path: First call allowed, then cooldown enforced
    // =========================================================================

    @Test
    fun isCallAllowed_firstCallForVaultId_allowed() {
        // First call for a vault that has never been called before is always allowed
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, "vault-1"))
    }

    @Test
    fun isCallAllowed_secondCallImmediately_blocked() {
        val vaultId = "vault-1"
        
        // First call succeeds
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        
        // Second call immediately after is blocked
        assertFalse(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
    }

    @Test
    fun isCallAllowed_afterCooldownExpires_allowed() {
        val vaultId = "vault-1"
        
        // First call
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        
        // Immediately blocked
        assertFalse(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        
        // Simulate time passing: manually set timestamp to 2+ seconds ago
        val key = "deep_link_call_${vaultId}_last_call_ms"
        val twoSecondsAgo = fakeNow - 2_100
        savedStateHandle[key] = twoSecondsAgo
        
        // After cooldown expires, call is allowed
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
    }

    // =========================================================================
    // Multiple vaults: Each vault has independent cooldown
    // =========================================================================

    @Test
    fun isCallAllowed_multipleVaults_independentCooldowns() {
        val vault1 = "vault-a"
        val vault2 = "vault-b"
        
        // First call for vault1
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vault1))
        
        // Vault2 is not yet in cooldown — first call allowed
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vault2))
        
        // Vault1 is still in cooldown
        assertFalse(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vault1))
        
        // Vault2 is also now in cooldown
        assertFalse(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vault2))
    }

    // =========================================================================
    // Cooldown duration: Verify exact timing
    // =========================================================================

    @Test
    fun remainingCooldownMs_immediately_returnsCorrectValue() {
        val vaultId = "vault-timed"
        
        // Make first call to establish timestamp
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        
        // Check remaining cooldown immediately
        val remaining = DeepLinkRateLimiter.remainingCooldownMs(savedStateHandle, vaultId)
        
        // Should be approximately 2000ms, allowing for test execution time
        assertTrue("Remaining cooldown should be close to 2000ms, got $remaining",
            remaining >= 1900 && remaining <= 2100)
    }

    @Test
    fun remainingCooldownMs_noCooldownActive_returnsZero() {
        val vaultId = "vault-none"
        
        // No call made yet
        assertEquals(0, DeepLinkRateLimiter.remainingCooldownMs(savedStateHandle, vaultId))
    }

    @Test
    fun remainingCooldownMs_afterExpiry_returnsZero() {
        val vaultId = "vault-expired"
        
        // Make first call
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        
        // Simulate time passing: set timestamp to 3 seconds ago (> 2s cooldown)
        val key = "deep_link_call_${vaultId}_last_call_ms"
        val threeSecondsAgo = fakeNow - 3_000
        savedStateHandle[key] = threeSecondsAgo
        
        // Cooldown has expired
        assertEquals(0, DeepLinkRateLimiter.remainingCooldownMs(savedStateHandle, vaultId))
    }

    // =========================================================================
    // State persistence: Survives SavedStateHandle round-trip
    // =========================================================================

    @Test
    fun isCallAllowed_statePersistedInHandle() {
        val vaultId = "vault-persist"
        
        // Make a call (records timestamp in SavedStateHandle)
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        
        // Verify state is in the handle
        val key = "deep_link_call_${vaultId}_last_call_ms"
        val timestamp = savedStateHandle.get<Any>(key)
        assertNotNull("Timestamp should be persisted in SavedStateHandle", timestamp)
    }

    // =========================================================================
    // Rapid repeated calls: Simulate malicious/accidental rapid taps
    // =========================================================================

    @Test
    fun isCallAllowed_rapidRepeatCalls_allBlocked() {
        val vaultId = "vault-spam"
        
        // Simulate rapid taps: first allowed, rest blocked
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        
        // Rapid succession of calls
        repeat(5) {
            assertFalse("Call $it should be blocked during cooldown",
                DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        }
    }

    @Test
    fun isCallAllowed_serialCalls_withDelay_allowed() {
        val vaultId = "vault-serial"
        
        // First call
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        
        // Advance the injected clock past the 2 s cooldown without sleeping
        fakeNow += 2_100
        
        // Next call should be allowed now
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
    }

    @Test
    fun isCallAllowed_clockMovedBackward_doesNotLockOut() {
        val vaultId = "vault-backward"
        
        // Record a call at a high monotonic time
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        
        // Clock restarts lower (e.g. after reboot): the stale timestamp must not block calls
        fakeNow = 5_000L
        assertTrue("A clock that moved backward must not lock the limiter out",
            DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        assertEquals(2_000L, DeepLinkRateLimiter.remainingCooldownMs(savedStateHandle, vaultId))
        
        // Normal cooldown resumes from the new timestamp
        assertFalse(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
    }

    // =========================================================================
    // Cleanup: Clear cooldown
    // =========================================================================

    @Test
    fun clearCooldown_removesState() {
        val vaultId = "vault-clear"
        
        // Make a call to establish state
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        
        // Verify it's in cooldown
        assertFalse(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
        
        // Clear the cooldown
        DeepLinkRateLimiter.clearCooldown(savedStateHandle, vaultId)
        
        // Now next call is allowed (cooldown cleared)
        assertTrue(DeepLinkRateLimiter.isCallAllowed(savedStateHandle, vaultId))
    }

    // =========================================================================
    // Edge cases: Empty vault ID, very long ID, special characters
    // =========================================================================

    @Test
    fun isCallAllowed_validVaultIds_acceptedAndRateLimited() {
        // Valid IDs from VaultDeepLinkParser allowlist
        val validIds = listOf("vault-1", "v_1", "V-A_Z-1-2-3", "a".repeat(128))
        
        for (id in validIds) {
            assertTrue("First call for $id should be allowed",
                DeepLinkRateLimiter.isCallAllowed(savedStateHandle, id))
            assertFalse("Second call for $id should be blocked",
                DeepLinkRateLimiter.isCallAllowed(savedStateHandle, id))
        }
    }
}

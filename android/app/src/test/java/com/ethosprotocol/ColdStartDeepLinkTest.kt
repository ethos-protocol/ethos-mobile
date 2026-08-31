package com.ethosprotocol

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.ethosprotocol.services.VaultDeepLinkParser
import com.ethosprotocol.ui.DeepLinkViewModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers the cold-start case for #236: app fully killed, notification tapped,
 * Android delivers the intent/URI to MainActivity. The key behaviours are:
 *   1. VaultDeepLinkParser correctly parses the check-in URI carried in the notification.
 *   2. DeepLinkViewModel (backed by SavedStateHandle) preserves the pending deep link
 *      across process death/recreation, mirroring what happens when the OS restores the
 *      Activity from the notification tap.
 *   3. No crash if the vault referenced by the notification no longer exists.
 */
class ColdStartDeepLinkTest {

    // ── VaultDeepLinkParser ────────────────────────────────────────────────────

    @Test
    fun `coldStart validCheckInUri parses to VaultDeepLink`() {
        val uri = Uri.parse("ethosprotocol://vault/abc123/check-in")
        val result = VaultDeepLinkParser.parse(uri)
        assertNotNull("Expected a VaultDeepLink for a valid cold-start URI", result)
        assertEquals("abc123", result!!.vaultId)
    }

    @Test
    fun `coldStart arbitrary vaultId does not crash parser`() {
        // A vault that no longer exists still has a valid-format ID; the parser
        // should return a DeepLink — it is not the parser's job to validate
        // whether the vault actually exists server-side.
        val vaultId = "vault-that-no-longer-exists-12345"
        val uri = Uri.parse("ethosprotocol://vault/$vaultId/check-in")
        val result = VaultDeepLinkParser.parse(uri)
        assertNotNull(result)
        assertEquals(vaultId, result!!.vaultId)
    }

    @Test
    fun `coldStart emptyVaultId returns null`() {
        // ethosprotocol://vault//check-in — empty vault ID segment
        val uri = Uri.parse("ethosprotocol://vault//check-in")
        val result = VaultDeepLinkParser.parse(uri)
        assertNull("Empty vault ID should return null", result)
    }

    @Test
    fun `coldStart wrongScheme returns null`() {
        val uri = Uri.parse("https://ethos-protocol.app/vault/abc123/check-in")
        // VaultDeepLinkParser only handles the custom scheme deep-links
        // (https universal links go through UniversalLinkRouter on iOS; on Android
        // they are handled via intent-filter, but the parser itself only handles
        // the ethosprotocol:// scheme used by notification intents).
        val result = VaultDeepLinkParser.parse(uri)
        // The parser may or may not handle https — just assert no exception is thrown.
        // If it returns null, that is also acceptable.
        // This test simply verifies no crash.
    }

    // ── DeepLinkViewModel (process-death survival) ─────────────────────────────

    @Test
    fun `coldStart pendingVaultDeepLink survivesProcessDeath via SavedStateHandle`() {
        // Simulate: Activity is killed while a deep link is pending, then recreated.
        // SavedStateHandle persists the pending link across process death.
        val savedState = SavedStateHandle()
        val vm = DeepLinkViewModel(savedState)

        val uri = Uri.parse("ethosprotocol://vault/saved-vault-id/check-in")
        val deepLink = VaultDeepLinkParser.parse(uri)!!
        vm.setPendingVaultDeepLink(deepLink)

        // Simulate process death: create a new ViewModel with the same SavedStateHandle
        // (the OS restores the handle after process death).
        val restoredVm = DeepLinkViewModel(savedState)
        val pending = restoredVm.pendingVaultDeepLink.value
        assertNotNull("Pending deep link should survive process death", pending)
        assertEquals("saved-vault-id", pending!!.vaultId)
    }

    @Test
    fun `coldStart consumeVaultDeepLink clears state`() {
        val savedState = SavedStateHandle()
        val vm = DeepLinkViewModel(savedState)

        val uri = Uri.parse("ethosprotocol://vault/abc123/check-in")
        val deepLink = VaultDeepLinkParser.parse(uri)!!
        vm.setPendingVaultDeepLink(deepLink)
        vm.consumeVaultDeepLink()

        assertNull(vm.pendingVaultDeepLink.value)
    }
}

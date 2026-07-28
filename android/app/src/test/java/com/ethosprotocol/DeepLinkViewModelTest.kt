package com.ethosprotocol

import androidx.lifecycle.SavedStateHandle
import com.ethosprotocol.services.VaultDeepLink
import com.ethosprotocol.services.VaultDeepLinkAction
import com.ethosprotocol.ui.DeepLinkViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [DeepLinkViewModel].
 *
 * These tests exercise the SavedStateHandle-backed persistence that was introduced to fix #93.
 * We simulate a configuration change / process death by:
 *   1. Setting state on a first ViewModel instance backed by a [SavedStateHandle].
 *   2. Creating a *second* ViewModel instance from the *same* handle (the exact mechanism the
 *      Android framework uses on recreation — the handle is parcelled and restored by the
 *      Activity's state machinery).
 * If the values survive across the two instances the bug described in #93 is fixed.
 */
class DeepLinkViewModelTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a [DeepLinkViewModel] backed by [handle] (default: empty handle). */
    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
        DeepLinkViewModel(handle)

    // -------------------------------------------------------------------------
    // Beneficiary-accept persistence
    // -------------------------------------------------------------------------

    @Test
    fun setBeneficiaryAccept_survives_recreation() {
        val handle = SavedStateHandle()
        val vm1 = viewModel(handle)

        vm1.setPendingBeneficiaryAccept("vault-abc")

        // Simulate recreation: new ViewModel instance, same handle.
        val vm2 = viewModel(handle)
        assertEquals("vault-abc", vm2.pendingBeneficiaryAcceptVaultId.value)
    }

    @Test
    fun beneficiaryAccept_initiallyNull() {
        assertNull(viewModel().pendingBeneficiaryAcceptVaultId.value)
    }

    @Test
    fun consumeBeneficiaryAccept_clearsState() {
        val vm = viewModel()
        vm.setPendingBeneficiaryAccept("vault-xyz")
        vm.consumeBeneficiaryAccept()
        assertNull(vm.pendingBeneficiaryAcceptVaultId.value)
    }

    @Test
    fun consumeBeneficiaryAccept_clearsState_afterRecreation() {
        val handle = SavedStateHandle()
        val vm1 = viewModel(handle)
        vm1.setPendingBeneficiaryAccept("vault-xyz")

        val vm2 = viewModel(handle)
        vm2.consumeBeneficiaryAccept()
        assertNull(vm2.pendingBeneficiaryAcceptVaultId.value)
    }

    // -------------------------------------------------------------------------
    // Vault deep-link persistence
    // -------------------------------------------------------------------------

    @Test
    fun setVaultDeepLink_survives_recreation() {
        val handle = SavedStateHandle()
        val vm1 = viewModel(handle)
        val deepLink = VaultDeepLink("vault-001", VaultDeepLinkAction.CHECK_IN)

        vm1.setPendingVaultDeepLink(deepLink)

        val vm2 = viewModel(handle)
        assertEquals(deepLink, vm2.pendingVaultDeepLink.value)
    }

    @Test
    fun vaultDeepLink_initiallyNull() {
        assertNull(viewModel().pendingVaultDeepLink.value)
    }

    @Test
    fun consumeVaultDeepLink_clearsState() {
        val vm = viewModel()
        vm.setPendingVaultDeepLink(VaultDeepLink("vault-002", VaultDeepLinkAction.WITHDRAW))
        vm.consumeVaultDeepLink()
        assertNull(vm.pendingVaultDeepLink.value)
    }

    @Test
    fun consumeVaultDeepLink_clearsState_afterRecreation() {
        val handle = SavedStateHandle()
        val vm1 = viewModel(handle)
        vm1.setPendingVaultDeepLink(VaultDeepLink("vault-003", VaultDeepLinkAction.VIEW_DETAILS))

        val vm2 = viewModel(handle)
        vm2.consumeVaultDeepLink()
        assertNull(vm2.pendingVaultDeepLink.value)
    }

    @Test
    fun vaultDeepLink_allActionsRoundTrip() {
        VaultDeepLinkAction.entries.forEach { action ->
            val handle = SavedStateHandle()
            val vm1 = viewModel(handle)
            val deepLink = VaultDeepLink("v-${action.pathSegment}", action)

            vm1.setPendingVaultDeepLink(deepLink)

            val vm2 = viewModel(handle)
            assertEquals(
                "Round-trip failed for action $action",
                deepLink,
                vm2.pendingVaultDeepLink.value
            )
        }
    }

    // -------------------------------------------------------------------------
    // Mutual exclusion (setting one clears the other from MainActivity logic)
    // -------------------------------------------------------------------------

    @Test
    fun setBeneficiaryAccept_doesNotAffectVaultDeepLink() {
        val vm = viewModel()
        vm.setPendingVaultDeepLink(VaultDeepLink("vault-004", VaultDeepLinkAction.CHECK_IN))
        // MainActivity's handleIncomingIntent clears the other field before setting the new one;
        // but DeepLinkViewModel itself does not enforce mutual exclusion — verify that setting
        // the beneficiary field does not silently wipe the deep-link field.
        vm.setPendingBeneficiaryAccept("vault-005")
        // deep link is still set (caller is responsible for clearing it, as MainActivity does)
        assertEquals(VaultDeepLink("vault-004", VaultDeepLinkAction.CHECK_IN), vm.pendingVaultDeepLink.value)
        assertEquals("vault-005", vm.pendingBeneficiaryAcceptVaultId.value)
    }

    // -------------------------------------------------------------------------
    // Process-death simulation: pre-populate handle before ViewModel creation
    // -------------------------------------------------------------------------

    @Test
    fun pendingBeneficiaryAccept_restoredFromParcelledState() {
        // Simulate a process-death restore where the OS parcels the SavedStateHandle
        // entries and restores them before the ViewModel is constructed.
        val restoredHandle = SavedStateHandle(
            mapOf(DeepLinkViewModel.KEY_BENEFICIARY_VAULT_ID to "vault-restored")
        )
        val vm = viewModel(restoredHandle)
        assertEquals("vault-restored", vm.pendingBeneficiaryAcceptVaultId.value)
    }

    @Test
    fun pendingVaultDeepLink_restoredFromParcelledState() {
        val restoredHandle = SavedStateHandle(
            mapOf(
                DeepLinkViewModel.KEY_DEEP_LINK_VAULT_ID to "vault-999",
                DeepLinkViewModel.KEY_DEEP_LINK_ACTION  to VaultDeepLinkAction.MANAGE_BENEFICIARY.pathSegment
            )
        )
        val vm = viewModel(restoredHandle)
        assertEquals(
            VaultDeepLink("vault-999", VaultDeepLinkAction.MANAGE_BENEFICIARY),
            vm.pendingVaultDeepLink.value
        )
    }

    @Test
    fun pendingVaultDeepLink_nullWhenOnlyVaultIdRestored() {
        // Partial state (vaultId without action) must not produce a half-constructed DeepLink.
        val restoredHandle = SavedStateHandle(
            mapOf(DeepLinkViewModel.KEY_DEEP_LINK_VAULT_ID to "vault-partial")
        )
        val vm = viewModel(restoredHandle)
        assertNull(vm.pendingVaultDeepLink.value)
    }

    @Test
    fun pendingVaultDeepLink_nullWhenOnlyActionRestored() {
        val restoredHandle = SavedStateHandle(
            mapOf(DeepLinkViewModel.KEY_DEEP_LINK_ACTION to VaultDeepLinkAction.CHECK_IN.pathSegment)
        )
        val vm = viewModel(restoredHandle)
        assertNull(vm.pendingVaultDeepLink.value)
    }
}

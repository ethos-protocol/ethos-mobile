package com.ethosprotocol

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.VaultViewModel
import com.ethosprotocol.ui.screens.VaultDeepLinkScreen
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for issue #64: VaultDeepLinkScreen must show an explicit loading indicator while
 * state.isLoading is true and vault is null, and only show "Vault not found" once loading
 * has completed and the vault genuinely isn't present (preventing false flash of error).
 *
 * Unlike this module's other Robolectric tests, this one deliberately does NOT use
 * @Config(manifest = Config.NONE): createComposeRule() hosts the composition in a real
 * androidx.activity.ComponentActivity under the hood (even though this isn't
 * createAndroidComposeRule), and Robolectric can't resolve/launch that activity without a
 * real manifest to establish the app's package/component context
 * (see https://github.com/robolectric/robolectric/pull/4736).
 */
@RunWith(RobolectricTestRunner::class)
class VaultDeepLinkScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `shows loading indicator when isLoading is true and vault is null`() {
        val vm = mockk<VaultViewModel>(relaxed = true)
        val state = MutableStateFlow(com.ethosprotocol.ui.VaultUiState(
            vaults = emptyList(),
            isLoading = true,
            error = null
        ))
        every { vm.state } returns state

        composeTestRule.setContent {
            VaultDeepLinkScreen(
                vaultId = "vault-123",
                actionPath = "view-details",
                onDone = {},
                vm = vm
            )
        }

        // Loading indicator should be visible
        composeTestRule.onNodeWithTag("loading", useUnmergedTree = true).assertExists()
        // "Vault not found" should NOT appear while loading
        composeTestRule.onNodeWithText("Vault not found").assertDoesNotExist()
    }

    @Test
    fun `shows Vault not found only after loading completes with no vault`() {
        val vm = mockk<VaultViewModel>(relaxed = true)
        val state = MutableStateFlow(com.ethosprotocol.ui.VaultUiState(
            vaults = emptyList(),
            isLoading = false,  // loading completed
            error = null
        ))
        every { vm.state } returns state

        composeTestRule.setContent {
            VaultDeepLinkScreen(
                vaultId = "vault-404",
                actionPath = "view-details",
                onDone = {},
                vm = vm
            )
        }

        // Loading indicator should NOT be visible
        composeTestRule.onNodeWithTag("loading", useUnmergedTree = true).assertDoesNotExist()
        // "Vault not found" SHOULD now be visible
        composeTestRule.onNodeWithText("Vault not found").assertExists()
    }

    @Test
    fun `does not show Vault not found when loading completes with vault present`() {
        val vm = mockk<VaultViewModel>(relaxed = true)
        val vault = Vault(
            id = "vault-999",
            owner = "GABC",
            beneficiary = "GXYZ",
            balance = 1_000_000L,
            checkInInterval = 86_400L,
            lastCheckIn = "2026-01-01T00:00:00Z",
            ttlRemaining = 86_400L,
            status = VaultStatus.active
        )
        val state = MutableStateFlow(com.ethosprotocol.ui.VaultUiState(
            vaults = listOf(vault),
            isLoading = false,
            error = null
        ))
        every { vm.state } returns state

        composeTestRule.setContent {
            VaultDeepLinkScreen(
                vaultId = "vault-999",
                actionPath = "view-details",
                onDone = {},
                vm = vm
            )
        }

        // "Vault not found" should NOT appear
        composeTestRule.onNodeWithText("Vault not found").assertDoesNotExist()
        // Vault card or details should be shown instead
        composeTestRule.onNodeWithText("Vault Details").assertExists()
    }
}

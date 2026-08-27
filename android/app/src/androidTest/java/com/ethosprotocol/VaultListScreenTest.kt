package com.ethosprotocol

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.screens.VaultListScreen
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class VaultListScreenTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createComposeRule()

    @Before fun setup() { hiltRule.inject() }

    @Test
    fun emptyState_showsCreatePrompt() {
        composeRule.setContent { VaultListScreen(onVaultClick = {}) }
        composeRule.onNodeWithText("No vaults yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun addButton_isDisplayed() {
        composeRule.setContent { VaultListScreen(onVaultClick = {}) }
        composeRule.onNodeWithContentDescription("Create vault").assertIsDisplayed()
    }

    // ── #214 Last-remaining-passkey sign-out warning ─────────────────────────

    @Test
    fun signOut_whenLastRemainingPasskey_showsBlockingWarning_insteadOfSigningOutImmediately() {
        var signedOut = false
        composeRule.setContent {
            VaultListScreen(
                onVaultClick = {},
                onSignOut = { signedOut = true },
                checkLastRemainingPasskey = { true }
            )
        }

        composeRule.onNodeWithContentDescription("Sign out").performClick()

        composeRule.onNodeWithText("This Is Your Only Passkey").assertIsDisplayed()
        assert(!signedOut) { "Sign-out must be blocked behind the confirmation, not fired immediately" }
    }

    @Test
    fun signOut_whenLastRemainingPasskey_confirmingWarning_signsOut() {
        var signedOut = false
        composeRule.setContent {
            VaultListScreen(
                onVaultClick = {},
                onSignOut = { signedOut = true },
                checkLastRemainingPasskey = { true }
            )
        }

        composeRule.onNodeWithContentDescription("Sign out").performClick()
        composeRule.onNodeWithText("Sign Out Anyway").performClick()

        assert(signedOut) { "Confirming the warning must proceed with sign-out" }
    }

    @Test
    fun signOut_whenNotLastRemainingPasskey_signsOutImmediately_withNoWarning() {
        var signedOut = false
        composeRule.setContent {
            VaultListScreen(
                onVaultClick = {},
                onSignOut = { signedOut = true },
                checkLastRemainingPasskey = { false }
            )
        }

        composeRule.onNodeWithContentDescription("Sign out").performClick()

        assert(signedOut) { "Sign-out should proceed immediately when it isn't the last passkey" }
        composeRule.onNodeWithText("This Is Your Only Passkey").assertDoesNotExist()
    }
}

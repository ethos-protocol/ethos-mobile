package com.ethosprotocol

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ethosprotocol.security.SignatureResult
import com.ethosprotocol.security.TamperWarningDialog
import com.ethosprotocol.security.shouldShowTamperWarning
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose tests for TamperWarningDialog (#820).
 *
 * The dialog is shown only for a failed signature result (SignatureResult.Mismatch)
 * via shouldShowTamperWarning, and is informational only — dismissible via the
 * "I Understand" confirm button (or outside tap), with no cancel button.
 */
@RunWith(RobolectricTestRunner::class)
class TamperWarningDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // --- When the dialog is shown: only for a failed signature result ---

    @Test
    fun `shouldShowTamperWarning is true only for a failed signature result`() {
        assertTrue(shouldShowTamperWarning(SignatureResult.Mismatch("deadbeef")))
        assertFalse(shouldShowTamperWarning(SignatureResult.Valid))
        assertFalse(shouldShowTamperWarning(SignatureResult.Unreadable(RuntimeException("pm failure"))))
        assertFalse(shouldShowTamperWarning(SignatureResult.NotConfigured))
    }

    // --- What the dialog shows when displayed ---

    @Test
    fun `dialog shows the tamper warning title and body when shown`() {
        composeTestRule.setContent {
            MaterialTheme {
                TamperWarningDialog(onDismiss = {})
            }
        }
        composeTestRule.onNodeWithText("Unverified App Installation").assertExists()
        composeTestRule.onNodeWithText("official Google Play Store", substring = true).assertExists()
        composeTestRule.onNodeWithText("I Understand").assertExists()
    }

    // --- Dismissal: dismissible only as specified (the I Understand button; no cancel) ---

    @Test
    fun `dialog is dismissible via the I Understand button`() {
        var dismissed = false
        composeTestRule.setContent {
            MaterialTheme {
                TamperWarningDialog(onDismiss = { dismissed = true })
            }
        }
        composeTestRule.onNodeWithText("I Understand").performClick()
        assertTrue("Tapping I Understand must dismiss the dialog", dismissed)
    }

    @Test
    fun `dialog exposes no cancel or dismiss button`() {
        composeTestRule.setContent {
            MaterialTheme {
                TamperWarningDialog(onDismiss = {})
            }
        }
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeTestRule.onNodeWithText("Dismiss").assertDoesNotExist()
    }
}

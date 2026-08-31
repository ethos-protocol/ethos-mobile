package com.ethosprotocol

import com.ethosprotocol.models.DestructiveConfirmation
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers the typed-confirmation gate for irreversible vault actions (#220):
 * the underlying action must never fire without an exact match on the
 * required text.
 */
class DestructiveConfirmationTest {

    @Test
    fun `isConfirmed false when enteredText empty`() {
        val confirmation = DestructiveConfirmation(requiredText = "my-vault")
        assertFalse(confirmation.isConfirmed)
    }

    @Test
    fun `isConfirmed false when enteredText does not match`() {
        val confirmation = DestructiveConfirmation(requiredText = "my-vault", enteredText = "my-vaul")
        assertFalse(confirmation.isConfirmed)
    }

    @Test
    fun `isConfirmed false when case differs`() {
        val confirmation = DestructiveConfirmation(requiredText = "my-vault", enteredText = "My-Vault")
        assertFalse(confirmation.isConfirmed)
    }

    @Test
    fun `isConfirmed true when enteredText matches exactly`() {
        val confirmation = DestructiveConfirmation(requiredText = "my-vault", enteredText = "my-vault")
        assertTrue(confirmation.isConfirmed)
    }

    @Test
    fun `isConfirmed false when requiredText empty`() {
        // An empty required text (e.g. a vault with no name) must never be
        // satisfiable by an empty entry — there is nothing to type either way.
        val confirmation = DestructiveConfirmation(requiredText = "")
        assertFalse(confirmation.isConfirmed)
    }

    @Test
    fun `confirmIfMatched does not fire action when unconfirmed`() {
        val confirmation = DestructiveConfirmation(requiredText = "my-vault")
        var actionFired = false
        confirmation.confirmIfMatched { actionFired = true }
        assertFalse("The destructive action must never fire without a matching confirmation", actionFired)
    }

    @Test
    fun `confirmIfMatched does not fire action for partial match`() {
        val confirmation = DestructiveConfirmation(requiredText = "my-vault", enteredText = "my-vault-extra")
        var actionFired = false
        confirmation.confirmIfMatched { actionFired = true }
        assertFalse(actionFired)
    }

    @Test
    fun `confirmIfMatched fires action when confirmed`() {
        val confirmation = DestructiveConfirmation(requiredText = "my-vault", enteredText = "my-vault")
        var actionFired = false
        confirmation.confirmIfMatched { actionFired = true }
        assertTrue(actionFired)
    }
}

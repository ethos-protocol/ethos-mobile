package com.ethosprotocol

import com.ethosprotocol.services.VaultDeepLinkAction
import com.ethosprotocol.services.VaultDeepLinkParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class VaultDeepLinkParserTest {

    private val defaultEventLogger = VaultDeepLinkParser.eventLogger

    @Before
    fun setup() {
        // eventLogger is a mutable singleton shared across the test JVM; guarantee a clean
        // slate regardless of test execution order.
        VaultDeepLinkParser.eventLogger = defaultEventLogger
    }

    @After
    fun teardown() {
        VaultDeepLinkParser.eventLogger = defaultEventLogger
    }

    @Test
    fun parseUrl_checkIn_returnsVaultDeepLink() {
        val result = VaultDeepLinkParser.parseUrl("ethosprotocol://vault/vault-abc-123/check-in")
        assertEquals("vault-abc-123", result?.vaultId)
        assertEquals(VaultDeepLinkAction.CHECK_IN, result?.action)
    }

    @Test
    fun parseUrl_withdraw_returnsVaultDeepLink() {
        val result = VaultDeepLinkParser.parseUrl("ethosprotocol://vault/vault-xyz/withdraw")
        assertEquals("vault-xyz", result?.vaultId)
        assertEquals(VaultDeepLinkAction.WITHDRAW, result?.action)
    }

    @Test
    fun parseUrl_viewDetails_returnsVaultDeepLink() {
        val result = VaultDeepLinkParser.parseUrl("ethosprotocol://vault/v1/view-details")
        assertEquals("v1", result?.vaultId)
        assertEquals(VaultDeepLinkAction.VIEW_DETAILS, result?.action)
    }

    @Test
    fun parseUrl_manageBeneficiary_returnsVaultDeepLink() {
        val result = VaultDeepLinkParser.parseUrl("ethosprotocol://vault/vault-42/manage-beneficiary")
        assertEquals("vault-42", result?.vaultId)
        assertEquals(VaultDeepLinkAction.MANAGE_BENEFICIARY, result?.action)
    }

    @Test
    fun parseUrl_unknownAction_returnsNull() {
        assertNull(VaultDeepLinkParser.parseUrl("ethosprotocol://vault/vault-1/unknown-action"))
    }

    @Test
    fun parseUrl_wrongScheme_returnsNull() {
        assertNull(VaultDeepLinkParser.parseUrl("https://ethos-protocol.app/vault/v1/check-in"))
    }

    @Test
    fun parseUrl_wrongHost_returnsNull() {
        assertNull(VaultDeepLinkParser.parseUrl("ethosprotocol://other/v1/check-in"))
    }

    @Test
    fun parseUrl_missingActionSegment_returnsNull() {
        assertNull(VaultDeepLinkParser.parseUrl("ethosprotocol://vault/v1"))
    }

    @Test
    fun parseUrl_success_logsExactlyOnce() {
        val loggedActions = mutableListOf<VaultDeepLinkAction>()
        VaultDeepLinkParser.eventLogger = VaultDeepLinkParser.EventLogger { loggedActions += it }

        VaultDeepLinkParser.parseUrl("ethosprotocol://vault/vault-abc-123/check-in")

        assertEquals(listOf(VaultDeepLinkAction.CHECK_IN), loggedActions)
    }

    @Test
    fun parseUrl_failure_doesNotLog() {
        val loggedActions = mutableListOf<VaultDeepLinkAction>()
        VaultDeepLinkParser.eventLogger = VaultDeepLinkParser.EventLogger { loggedActions += it }

        VaultDeepLinkParser.parseUrl("ethosprotocol://vault/vault-1/unknown-action")

        assertEquals(emptyList<VaultDeepLinkAction>(), loggedActions)
    }
}

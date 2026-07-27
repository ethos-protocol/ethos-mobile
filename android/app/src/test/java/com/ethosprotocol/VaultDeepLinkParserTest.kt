package com.ethosprotocol

import com.ethosprotocol.services.VaultDeepLinkAction
import com.ethosprotocol.services.VaultDeepLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [VaultDeepLinkParser] and [VaultDeepLinkParser.isValidVaultId].
 *
 * The happy-path tests at the top verify correct parsing of well-formed URIs.
 *
 * The adversarial section (see #96) explicitly tests the attack surface described in the
 * source comment on [VaultDeepLinkParser] — vault IDs are interpolated directly into API
 * paths and Compose navigation routes, so any character outside `[A-Za-z0-9_-]{1,128}` must
 * be rejected *before* the value reaches those consumers.
 *
 * Cross-link: #37 — iOS's port of the same validation should maintain test parity with this
 * file.
 */
class VaultDeepLinkParserTest {

    // =========================================================================
    // Happy-path parsing
    // =========================================================================

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

    // =========================================================================
    // isValidVaultId — allowlist boundaries
    // =========================================================================

    @Test
    fun isValidVaultId_alphanumericHyphenUnderscore_accepted() {
        assertTrue(VaultDeepLinkParser.isValidVaultId("vault-ABC_123"))
    }

    @Test
    fun isValidVaultId_singleChar_accepted() {
        assertTrue(VaultDeepLinkParser.isValidVaultId("a"))
    }

    @Test
    fun isValidVaultId_exactly128Chars_accepted() {
        val id = "a".repeat(128)
        assertTrue("128-char ID should be valid", VaultDeepLinkParser.isValidVaultId(id))
    }

    // =========================================================================
    // #96 — Adversarial cases: characters called out in the source comment
    // =========================================================================

    // ── Path separators ───────────────────────────────────────────────────────

    @Test
    fun isValidVaultId_forwardSlash_rejected() {
        // "/" can smuggle additional path segments into an API request or navigation route.
        assertFalse(VaultDeepLinkParser.isValidVaultId("vault/evil"))
    }

    @Test
    fun isValidVaultId_backSlash_rejected() {
        assertFalse(VaultDeepLinkParser.isValidVaultId("vault\\evil"))
    }

    // ── Percent-encoding / traversal sequences ────────────────────────────────

    @Test
    fun isValidVaultId_percentChar_rejected() {
        // "%" is the percent-encoding escape prefix; a raw "%" must be rejected so that
        // a value like "%2F" cannot be decoded downstream into a "/" path separator.
        assertFalse(VaultDeepLinkParser.isValidVaultId("vault%2Fevil"))
    }

    @Test
    fun isValidVaultId_percentEncodedDotDot_rejected() {
        // "%2E%2E" decodes to "../" — classic path traversal.
        assertFalse(VaultDeepLinkParser.isValidVaultId("%2E%2E%2Fetc%2Fpasswd"))
    }

    @Test
    fun isValidVaultId_percentEncodedSlash_rejected() {
        assertFalse(VaultDeepLinkParser.isValidVaultId("vault%2Fsecret"))
    }

    @Test
    fun isValidVaultId_dotDotSlash_rejected() {
        // Literal "../" path traversal attempt.
        assertFalse(VaultDeepLinkParser.isValidVaultId("../../etc/passwd"))
    }

    // ── Query / fragment delimiters ───────────────────────────────────────────

    @Test
    fun isValidVaultId_questionMark_rejected() {
        // "?" can append a query string to an interpolated URL path.
        assertFalse(VaultDeepLinkParser.isValidVaultId("vault?admin=true"))
    }

    @Test
    fun isValidVaultId_hashChar_rejected() {
        // "#" would truncate the path at a fragment boundary in some URL parsers.
        assertFalse(VaultDeepLinkParser.isValidVaultId("vault#fragment"))
    }

    // ── Whitespace and null bytes ─────────────────────────────────────────────

    @Test
    fun isValidVaultId_space_rejected() {
        assertFalse(VaultDeepLinkParser.isValidVaultId("vault id"))
    }

    @Test
    fun isValidVaultId_newline_rejected() {
        assertFalse(VaultDeepLinkParser.isValidVaultId("vault\nid"))
    }

    @Test
    fun isValidVaultId_nullByte_rejected() {
        assertFalse(VaultDeepLinkParser.isValidVaultId("vault\u0000id"))
    }

    // ── Special URL characters ────────────────────────────────────────────────

    @Test
    fun isValidVaultId_atSign_rejected() {
        assertFalse(VaultDeepLinkParser.isValidVaultId("user@host"))
    }

    @Test
    fun isValidVaultId_colon_rejected() {
        assertFalse(VaultDeepLinkParser.isValidVaultId("vault:evil"))
    }

    @Test
    fun isValidVaultId_dot_rejected() {
        // "." is not in the allowlist; "." alone or ".." could be path-traversal fragments.
        assertFalse(VaultDeepLinkParser.isValidVaultId(".."))
    }

    @Test
    fun isValidVaultId_angleAngleBrackets_rejected() {
        assertFalse(VaultDeepLinkParser.isValidVaultId("<script>"))
    }

    // ── Over-length ───────────────────────────────────────────────────────────

    @Test
    fun isValidVaultId_129Chars_rejected() {
        // The allowlist caps IDs at 128 characters to bound path length.
        val id = "a".repeat(129)
        assertFalse("129-char ID should be rejected", VaultDeepLinkParser.isValidVaultId(id))
    }

    @Test
    fun isValidVaultId_veryLong_rejected() {
        val id = "a".repeat(1000)
        assertFalse("1000-char ID should be rejected", VaultDeepLinkParser.isValidVaultId(id))
    }

    // ── Empty string ─────────────────────────────────────────────────────────

    @Test
    fun isValidVaultId_empty_rejected() {
        // The regex requires at least one character ({1,128}).
        assertFalse(VaultDeepLinkParser.isValidVaultId(""))
    }

    // =========================================================================
    // #96 — Adversarial cases: parseUrl rejects IDs with disallowed characters
    // =========================================================================

    @Test
    fun parseUrl_vaultIdWithSlash_returnsNull() {
        // URL_PATTERN captures everything up to the next "/", so "vault/evil" becomes two
        // segments — the second is the action, which won't match — resulting in null.
        assertNull(
            VaultDeepLinkParser.parseUrl("ethosprotocol://vault/vault/evil/check-in")
        )
    }

    @Test
    fun parseUrl_vaultIdWithPercentEncoding_returnsNull() {
        // Even if the URL regex matches, isValidVaultId must reject the "%" character.
        assertNull(
            VaultDeepLinkParser.parseUrl("ethosprotocol://vault/vault%2Fevil/check-in")
        )
    }

    @Test
    fun parseUrl_vaultIdWithQuestionMark_returnsNull() {
        assertNull(
            VaultDeepLinkParser.parseUrl("ethosprotocol://vault/vault?x=1/check-in")
        )
    }

    @Test
    fun parseUrl_vaultIdWithHash_returnsNull() {
        assertNull(
            VaultDeepLinkParser.parseUrl("ethosprotocol://vault/vault#frag/check-in")
        )
    }

    @Test
    fun parseUrl_overLengthVaultId_returnsNull() {
        val longId = "a".repeat(129)
        assertNull(
            VaultDeepLinkParser.parseUrl("ethosprotocol://vault/$longId/check-in")
        )
    }

    @Test
    fun parseUrl_traversalVaultId_returnsNull() {
        assertNull(
            VaultDeepLinkParser.parseUrl("ethosprotocol://vault/../../etc/passwd/check-in")
        )
    }
}


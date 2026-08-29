package com.ethosprotocol

import com.ethosprotocol.models.StellarAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [StellarAddress.isValidPublicKey].
 *
 * All fixtures are taken directly from `shared/stellar-validation-spec.md` (#264, #113)
 * so the same valid/invalid addresses are tested identically on iOS and Android.
 * If a fixture is added or changed in the spec, update both this file and
 * `ios/EthosProtocol/Tests/EthosProtocolTests.swift` (StellarAddressTests).
 */
class StellarAddressTest {

    // -------------------------------------------------------------------------
    // Valid addresses: public keys (G-addresses)
    // -------------------------------------------------------------------------

    // Verified valid StrKey ed25519 public keys (correct length, "G" prefix,
    // version byte 0x30, and valid CRC-16/XModem checksum).

    @Test
    fun `isValidPublicKey accepts all-zero payload address`() {
        assertTrue(StellarAddress.isValidPublicKey(
            "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
        ))
    }

    @Test
    fun `isValidPublicKey accepts non-trivial payload address`() {
        assertTrue(StellarAddress.isValidPublicKey(
            "GAAACAQDAQCQMBYIBEFAWDANBYHRAEISCMKBKFQXDAMRUGY4DUPB7JZX"
        ))
    }

    @Test
    fun `isValidPublicKey accepts third canonical valid address`() {
        assertTrue(StellarAddress.isValidPublicKey(
            "GB5JRTBIPHQBDXIEBQDEBZKHQ7DR5OWU5W2MWGGKXESYIYNEGQFRNEP7"
        ))
    }

    // -------------------------------------------------------------------------
    // Valid addresses: muxed accounts (M-addresses)
    // -------------------------------------------------------------------------

    @Test
    fun `isValidPublicKey accepts muxed account with memo ID zero`() {
        assertTrue(StellarAddress.isValidPublicKey(
            "MA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJUAAAAAAAAAAAACJUQ"
        ))
    }

    @Test
    fun `isValidPublicKey accepts muxed account with large memo ID`() {
        assertTrue(StellarAddress.isValidPublicKey(
            "MA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVAAAAAAAAAAAAAJLK"
        ))
    }

    // -------------------------------------------------------------------------
    // Invalid: wrong checksum
    // -------------------------------------------------------------------------

    @Test
    fun `isValidPublicKey rejects G-address with wrong checksum`() {
        // Same as the second valid address but the last character is changed
        // from 'X' to 'A', corrupting the checksum.
        assertFalse(StellarAddress.isValidPublicKey(
            "GAAACAQDAQCQMBYIBEFAWDANBYHRAEISCMKBKFQXDAMRUGY4DUPB7JZA"
        ))
    }

    @Test
    fun `isValidPublicKey rejects M-address with wrong checksum`() {
        assertFalse(StellarAddress.isValidPublicKey(
            "MA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJUAAAAAAAAAAAACJUR"
        ))
    }

    // -------------------------------------------------------------------------
    // Invalid: wrong length
    // -------------------------------------------------------------------------

    @Test
    fun `isValidPublicKey rejects G-address that is too short`() {
        // 55 characters — one less than required.
        assertFalse(StellarAddress.isValidPublicKey(
            "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAW"
        ))
    }

    @Test
    fun `isValidPublicKey rejects G-address that is too long`() {
        // 57 characters — one more than required.
        assertFalse(StellarAddress.isValidPublicKey(
            "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
        ))
    }

    @Test
    fun `isValidPublicKey rejects M-address that is too short`() {
        // 56 characters — should be 69 for an M-address.
        // This looks like it has "M" prefix but is too short.
        assertFalse(StellarAddress.isValidPublicKey(
            "MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
        ))
    }

    @Test
    fun `isValidPublicKey rejects M-address that is too long`() {
        // 70 characters — one more than required.
        assertFalse(StellarAddress.isValidPublicKey(
            "MA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJUAAAAAAAAAAAACJUQA"
        ))
    }

    // -------------------------------------------------------------------------
    // Invalid: character-set violations
    // -------------------------------------------------------------------------

    @Test
    fun `isValidPublicKey rejects lowercase address`() {
        assertFalse(StellarAddress.isValidPublicKey(
            "gaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaawhf"
        ))
    }

    @Test
    fun `isValidPublicKey rejects address containing zero digit`() {
        // '0' is not in the Stellar base32 alphabet [A-Z2-7].
        assertFalse(StellarAddress.isValidPublicKey(
            "GAAAAAAAAAAAAAAAAAAAAAAAAAAA0AAAAAAAAAAAAAAAAAAAAAAAAAWHF"
        ))
    }

    @Test
    fun `isValidPublicKey rejects address containing one digit`() {
        // '1' is not in the Stellar base32 alphabet [A-Z2-7].
        assertFalse(StellarAddress.isValidPublicKey(
            "GAAAAAAAAAAAAAAAAAAAAAAAAAAA1AAAAAAAAAAAAAAAAAAAAAAAAAWHF"
        ))
    }

    // -------------------------------------------------------------------------
    // Invalid: empty / obviously wrong input
    // -------------------------------------------------------------------------

    @Test
    fun `isValidPublicKey rejects empty string`() {
        assertFalse(StellarAddress.isValidPublicKey(""))
    }

    @Test
    fun `isValidPublicKey rejects arbitrary non-address string`() {
        assertFalse(StellarAddress.isValidPublicKey("not-a-stellar-address"))
    }

    // -------------------------------------------------------------------------
    // Sanitization
    // -------------------------------------------------------------------------

    @Test
    fun `sanitize removes leading and trailing whitespace`() {
        val sanitized = StellarAddress.sanitize("  GA7Q  ")
        assertTrue(sanitized.startsWith("GA7Q"))
        assertTrue(!sanitized.startsWith(" "))
        assertTrue(!sanitized.endsWith(" "))
    }

    @Test
    fun `sanitize removes zero-width space`() {
        val withZWS = "GA7Q\u200BYNF7"
        val sanitized = StellarAddress.sanitize(withZWS)
        assertEquals("GA7QYNF7", sanitized)
    }

    @Test
    fun `sanitize removes zero-width joiner and non-joiner`() {
        val withInvisible = "GA7Q\u200C\u200DYNF7"
        val sanitized = StellarAddress.sanitize(withInvisible)
        assertEquals("GA7QYNF7", sanitized)
    }

    @Test
    fun `sanitize removes direction marks`() {
        val withDirMarks = "\u200EGA7Q\u200FYNF7"
        val sanitized = StellarAddress.sanitize(withDirMarks)
        assertEquals("GA7QYNF7", sanitized)
    }

    @Test
    fun `isValidPublicKey rejects addresses with leading whitespace`() {
        // Validator expects pre-sanitized input
        assertFalse(StellarAddress.isValidPublicKey(
            " GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
        ))
    }

    @Test
    fun `isValidPublicKey works after sanitize`() {
        val messy = "  GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF  "
        val sanitized = StellarAddress.sanitize(messy)
        assertTrue(StellarAddress.isValidPublicKey(sanitized))
    }

// MARK: - Memo Field Support Tests

class MemoValidatorTest {

    @Test
    fun `isValidTextMemo accepts short text`() {
        assertTrue(MemoValidator.isValidTextMemo("hello"))
    }

    @Test
    fun `isValidTextMemo accepts max length text`() {
        // 28 bytes of ASCII
        val maxText = "a".repeat(28)
        assertTrue(MemoValidator.isValidTextMemo(maxText))
    }

    @Test
    fun `isValidTextMemo rejects text over 28 bytes`() {
        val tooLong = "a".repeat(29)
        assertFalse(MemoValidator.isValidTextMemo(tooLong))
    }

    @Test
    fun `isValidTextMemo accepts utf8 text within byte limit`() {
        // "🚀" is 4 bytes in UTF-8
        val emoji = "🚀".repeat(7) // 28 bytes total
        assertTrue(MemoValidator.isValidTextMemo(emoji))
    }

    @Test
    fun `isValidTextMemo rejects utf8 text exceeding byte limit`() {
        // "🚀" is 4 bytes, 8 repetitions = 32 bytes
        val tooManyEmoji = "🚀".repeat(8)
        assertFalse(MemoValidator.isValidTextMemo(tooManyEmoji))
    }

    @Test
    fun `isValidIDMemo accepts valid id`() {
        assertTrue(MemoValidator.isValidIDMemo("12345"))
    }

    @Test
    fun `isValidIDMemo accepts zero`() {
        assertTrue(MemoValidator.isValidIDMemo("0"))
    }

    @Test
    fun `isValidIDMemo accepts max uint64`() {
        assertTrue(MemoValidator.isValidIDMemo("18446744073709551615"))
    }

    @Test
    fun `isValidIDMemo rejects negative number`() {
        assertFalse(MemoValidator.isValidIDMemo("-1"))
    }

    @Test
    fun `isValidIDMemo rejects non-numeric`() {
        assertFalse(MemoValidator.isValidIDMemo("not-a-number"))
    }

    @Test
    fun `isValidIDMemo rejects empty string`() {
        assertFalse(MemoValidator.isValidIDMemo(""))
    }

    @Test
    fun `isValidHashMemo accepts valid hash`() {
        val validHash = "a".repeat(64)
        assertTrue(MemoValidator.isValidHashMemo(validHash))
    }

    @Test
    fun `isValidHashMemo accepts mixed hex`() {
        val hexHash = "abcdef0123456789" + "a".repeat(48)
        assertTrue(MemoValidator.isValidHashMemo(hexHash))
    }

    @Test
    fun `isValidHashMemo rejects too short`() {
        val tooShort = "a".repeat(63)
        assertFalse(MemoValidator.isValidHashMemo(tooShort))
    }

    @Test
    fun `isValidHashMemo rejects too long`() {
        val tooLong = "a".repeat(65)
        assertFalse(MemoValidator.isValidHashMemo(tooLong))
    }

    @Test
    fun `isValidHashMemo rejects non-hex characters`() {
        val nonHex = "G".repeat(64) // G is not in hex
        assertFalse(MemoValidator.isValidHashMemo(nonHex))
    }
}

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

    @Test
    fun `isValidPublicKey rejects blank whitespace string`() {
        assertFalse(StellarAddress.isValidPublicKey("   "))
    }
}

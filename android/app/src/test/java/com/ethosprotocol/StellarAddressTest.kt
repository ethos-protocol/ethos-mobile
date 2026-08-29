package com.ethosprotocol

import com.ethosprotocol.models.StellarAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [StellarAddress.isValidPublicKey].
 *
 * All fixtures are taken directly from `shared/stellar-validation-spec.md` (#113)
 * so the same valid/invalid addresses are tested identically on iOS and Android.
 * If a fixture is added or changed in the spec, update both this file and
 * `ios/EthosProtocol/Tests/EthosProtocolTests.swift` (StellarAddressTests).
 */
class StellarAddressTest {

    // -------------------------------------------------------------------------
    // Valid addresses — all must be accepted
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
    // Invalid: wrong checksum
    // -------------------------------------------------------------------------

    @Test
    fun `isValidPublicKey rejects address with wrong checksum`() {
        // Same as the second valid address but the last character is changed
        // from 'X' to 'A', corrupting the checksum.
        assertFalse(StellarAddress.isValidPublicKey(
            "GAAACAQDAQCQMBYIBEFAWDANBYHRAEISCMKBKFQXDAMRUGY4DUPB7JZA"
        ))
    }

    // -------------------------------------------------------------------------
    // Invalid: wrong prefix
    // -------------------------------------------------------------------------

    @Test
    fun `isValidPublicKey rejects address with wrong prefix`() {
        // Replace 'G' with 'M' — not a valid ed25519 public key version prefix.
        assertFalse(StellarAddress.isValidPublicKey(
            "MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
        ))
    }

    // -------------------------------------------------------------------------
    // Invalid: wrong length
    // -------------------------------------------------------------------------

    @Test
    fun `isValidPublicKey rejects address that is too short`() {
        // 55 characters — one less than required.
        assertFalse(StellarAddress.isValidPublicKey(
            "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAW"
        ))
    }

    @Test
    fun `isValidPublicKey rejects address that is too long`() {
        // 57 characters — one more than required.
        assertFalse(StellarAddress.isValidPublicKey(
            "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
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

    // -------------------------------------------------------------------------
    // Mutation-testing gap-fill (see shared/MUTATION_TESTING.md).
    //
    // The cases above exercise length, prefix, character-set, and a
    // last-character checksum corruption, but a mutation-testing pass
    // (PIT-style) against this file found two reject conditions from
    // `shared/stellar-validation-spec.md` that survived every existing test:
    // the version-byte check (step 5) and a checksum corruption that isn't
    // at the final character (step 6, different code path than the
    // last-char case above). Both are added below with fixtures generated
    // directly from the spec's algorithm so they fail for the *specific*
    // reason named, not coincidentally.
    // -------------------------------------------------------------------------

    @Test
    fun `isValidPublicKey rejects address with correct prefix and checksum but wrong version byte`() {
        // Decodes to a valid 35-byte structure with an internally-consistent
        // CRC-16/XModem checksum, but decoded[0] == 0x31, not the required
        // 0x30. Prefix ('G'), length, and character-set checks all pass —
        // only step 5 (version byte) catches this. A mutant that deletes or
        // inverts the version-byte comparison would let this through.
        assertFalse(StellarAddress.isValidPublicKey(
            "GEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABBDI"
        ))
    }

    @Test
    fun `isValidPublicKey rejects address with checksum corrupted in the middle, not the last char`() {
        // Same payload as the all-zero valid address, but character index 27
        // (well before the two trailing checksum characters) is flipped.
        // This exercises the checksum comparison against a corruption that
        // propagates through the middle of the decoded payload, rather than
        // only ever testing a corruption confined to the final character.
        assertFalse(StellarAddress.isValidPublicKey(
            "GAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
        ))
    }

    @Test
    fun `isValidPublicKey rejects address containing invalid character as the last character`() {
        // Character-set violations were only tested mid-string previously;
        // a mutant in a loop's boundary condition (e.g. `< length - 1`
        // instead of `< length`) would only be caught by checking the edge.
        assertFalse(StellarAddress.isValidPublicKey(
            "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWH0"
        ))
    }
}

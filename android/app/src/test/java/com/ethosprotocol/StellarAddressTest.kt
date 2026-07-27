package com.ethosprotocol

import com.ethosprotocol.models.StellarAddress
import org.junit.Assert.*
import org.junit.Test

// Verified valid StrKey ed25519 public keys (correct length, "G" prefix, version
// byte, and CRC16/XModem checksum) — same vectors used by iOS's StellarAddressTests
// so both platforms are validated against the same known-good/known-bad addresses.
class StellarAddressTest {

    private val validAddress = "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
    private val validAddress2 = "GAAACAQDAQCQMBYIBEFAWDANBYHRAEISCMKBKFQXDAMRUGY4DUPB7JZX"

    @Test
    fun `accepts well-formed addresses`() {
        assertTrue(StellarAddress.isValidPublicKey(validAddress))
        assertTrue(StellarAddress.isValidPublicKey(validAddress2))
    }

    @Test
    fun `rejects bad checksum`() {
        // Same as validAddress2 but with the final checksum character flipped.
        assertFalse(StellarAddress.isValidPublicKey("GAAACAQDAQCQMBYIBEFAWDANBYHRAEISCMKBKFQXDAMRUGY4DUPB7JZA"))
    }

    @Test
    fun `rejects wrong prefix`() {
        assertFalse(StellarAddress.isValidPublicKey("M" + validAddress.drop(1)))
    }

    @Test
    fun `rejects too short`() {
        assertFalse(StellarAddress.isValidPublicKey(validAddress.dropLast(1)))
    }

    @Test
    fun `rejects too long`() {
        assertFalse(StellarAddress.isValidPublicKey(validAddress + "A"))
    }

    @Test
    fun `rejects lowercase`() {
        assertFalse(StellarAddress.isValidPublicKey(validAddress.lowercase()))
    }

    @Test
    fun `rejects non base32 characters`() {
        val chars = validAddress.toCharArray()
        chars[10] = '0' // '0' is not in the Stellar base32 alphabet (A-Z, 2-7)
        assertFalse(StellarAddress.isValidPublicKey(String(chars)))
    }

    @Test
    fun `rejects empty string`() {
        assertFalse(StellarAddress.isValidPublicKey(""))
    }
}

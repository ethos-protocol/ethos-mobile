package com.ethosprotocol.models

/**
 * Validates Stellar addresses: both ed25519 public keys (G..., 56 chars) and
 * muxed accounts (M..., 69 chars per SEP-0023).
 *
 * Implements the algorithm specified in `shared/stellar-validation-spec.md` (#264, #113).
 * Dependency-free: no external Stellar SDK — only the checks the app needs.
 *
 * Public key structure per StrKey spec:
 *   byte[0]      : version byte 0x30 (= 6 shl 3, ed25519 public key)
 *   byte[1..32]  : 32-byte ed25519 public key payload
 *   byte[33..34] : CRC-16/XModem of byte[0..32], little-endian
 *   Base32-encoded → exactly 56 uppercase characters, always starting with "G"
 *
 * Muxed account structure per SEP-0023:
 *   byte[0]      : version byte 0x60 (= 12 shl 3, muxed ed25519 key)
 *   byte[1..32]  : 32-byte ed25519 public key payload (base account)
 *   byte[33..40] : 8-byte memo ID (big-endian)
 *   byte[41..42] : CRC-16/XModem of byte[0..40], little-endian
 *   Base32-encoded → exactly 69 uppercase characters, always starting with "M"
 */
object StellarAddress {

    private val base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val charToValue: Map<Char, Int> = base32Alphabet.mapIndexed { index, c -> c to index }.toMap()
    private const val ED25519_VERSION_BYTE: Byte = (6 shl 3).toByte() // 0x30 = 48
    private const val MUXED_VERSION_BYTE: Byte = (12 shl 3).toByte() // 0x60 = 96

    /**
     * Returns `true` if [value] is a syntactically valid Stellar address:
     * - A public key (G-address, 56 chars, ed25519)
     * - A muxed account (M-address, 69 chars, SEP-0023)
     *
     * Both formats are validated with CRC-16/XModem checksum verification.
     */
    fun isValidPublicKey(value: String): Boolean {
        return when {
            value.length == 56 && value[0] == 'G' -> validatePublicKey(value)
            value.length == 69 && value[0] == 'M' -> validateMuxedAccount(value)
            else -> false
        }
    }

    /**
     * Validates a G-address (ed25519 public key, 56 chars).
     * Steps:
     * 1. Length must be exactly 56.
     * 2. First character must be 'G'.
     * 3. All characters must be in [A-Z2-7] (RFC 4648 base32, no padding).
     * 4. Base32-decode to 35 bytes.
     * 5. Decoded byte[0] must equal version byte 0x30.
     * 6. CRC-16/XModem of decoded[0..32] must match decoded[33..34] (little-endian).
     */
    private fun validatePublicKey(value: String): Boolean {
        // Step 3: character set — all chars must be in [A-Z2-7]
        if (value.any { it !in charToValue }) return false

        // Step 4: base32 decode
        val decoded = base32Decode(value) ?: return false
        if (decoded.size != 35) return false

        // Step 5: version byte
        if (decoded[0] != ED25519_VERSION_BYTE) return false

        // Step 6: CRC-16/XModem checksum
        val payload = decoded.sliceArray(0 until 33)
        val expectedCrc = crc16XModem(payload)
        val actualCrc = (decoded[33].toInt() and 0xFF) or ((decoded[34].toInt() and 0xFF) shl 8)
        return expectedCrc == actualCrc
    }

    /**
     * Validates an M-address (muxed account, 69 chars, SEP-0023).
     * Steps:
     * 1. Length must be exactly 69.
     * 2. First character must be 'M'.
     * 3. All characters must be in [A-Z2-7] (RFC 4648 base32, no padding).
     * 4. Base32-decode to 43 bytes.
     * 5. Decoded byte[0] must equal version byte 0x60.
     * 6. CRC-16/XModem of decoded[0..40] must match decoded[41..42] (little-endian).
     */
    private fun validateMuxedAccount(value: String): Boolean {
        // Step 3: character set — all chars must be in [A-Z2-7]
        if (value.any { it !in charToValue }) return false

        // Step 4: base32 decode
        val decoded = base32Decode(value) ?: return false
        if (decoded.size != 43) return false

        // Step 5: version byte
        if (decoded[0] != MUXED_VERSION_BYTE) return false

        // Step 6: CRC-16/XModem checksum (covers version byte, key, and memo ID)
        val payload = decoded.sliceArray(0 until 41)
        val expectedCrc = crc16XModem(payload)
        val actualCrc = (decoded[41].toInt() and 0xFF) or ((decoded[42].toInt() and 0xFF) shl 8)
        return expectedCrc == actualCrc
    }

    /**
     * Decodes a base32-encoded string (RFC 4648, no padding) into a byte array.
     * Returns `null` if any character is not in the base32 alphabet.
     */
    private fun base32Decode(input: String): ByteArray? {
        var bitBuffer = 0
        var bitCount = 0
        val result = mutableListOf<Byte>()
        for (char in input) {
            val value = charToValue[char] ?: return null
            bitBuffer = (bitBuffer shl 5) or value
            bitCount += 5
            if (bitCount >= 8) {
                bitCount -= 8
                result.add(((bitBuffer ushr bitCount) and 0xFF).toByte())
            }
        }
        return result.toByteArray()
    }

    /**
     * CRC-16/XModem: polynomial 0x1021, init 0x0000, no reflection, no XOR-out.
     * Matches the pseudocode in `shared/stellar-validation-spec.md`.
     */
    private fun crc16XModem(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}

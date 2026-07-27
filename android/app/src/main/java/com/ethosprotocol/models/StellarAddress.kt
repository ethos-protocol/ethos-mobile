package com.ethosprotocol.models

// Validates Stellar "G..." account IDs (StrKey-encoded ed25519 public keys) so a
// malformed beneficiary address is caught before it round-trips to the server.
// Structure per the StrKey spec: 1-byte version (6 << 3 for an ed25519 public
// key) + 32-byte payload + 2-byte CRC16/XModem checksum, base32-encoded
// (RFC 4648, no padding) to exactly 56 characters starting with "G". Kept
// dependency-free (no external Stellar SDK) since this is the only check the
// app needs. Mirrors iOS's StellarAddress.swift (#113).
object StellarAddress {
    private val base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray()
    private const val ED25519_PUBLIC_KEY_VERSION_BYTE: Int = 6 shl 3

    fun isValidPublicKey(value: String): Boolean {
        if (value.length != 56 || !value.startsWith("G")) return false
        val decoded = base32Decode(value) ?: return false
        if (decoded.size != 35) return false
        if ((decoded[0].toInt() and 0xFF) != ED25519_PUBLIC_KEY_VERSION_BYTE) return false

        val versionAndPayload = decoded.copyOfRange(0, 33)
        val expectedChecksum = crc16XModem(versionAndPayload)
        val actualChecksum = (decoded[33].toInt() and 0xFF) or ((decoded[34].toInt() and 0xFF) shl 8)
        return expectedChecksum == actualChecksum
    }

    private fun base32Decode(string: String): ByteArray? {
        val charIndex = HashMap<Char, Int>()
        base32Alphabet.forEachIndexed { i, c -> charIndex[c] = i }

        var bitBuffer = 0L
        var bitCount = 0
        val bytes = mutableListOf<Byte>()
        for (char in string) {
            val charValue = charIndex[char] ?: return null
            bitBuffer = (bitBuffer shl 5) or charValue.toLong()
            bitCount += 5
            if (bitCount >= 8) {
                bitCount -= 8
                bytes.add(((bitBuffer shr bitCount) and 0xFF).toByte())
            }
        }
        return bytes.toByteArray()
    }

    private fun crc16XModem(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = (crc xor ((byte.toInt() and 0xFF) shl 8)) and 0xFFFF
            repeat(8) {
                crc = (if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1) and 0xFFFF
            }
        }
        return crc
    }
}

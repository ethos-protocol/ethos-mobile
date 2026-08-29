import Foundation

// Validates Stellar addresses: both ed25519 public keys (G..., 56 chars) and
// muxed accounts (M..., 69 chars per SEP-0023) so a malformed beneficiary address
// is caught before it round-trips to the server.
// Public key structure: 1-byte version (6 << 3 for ed25519 public key) + 32-byte
// payload + 2-byte CRC16/XModem checksum, base32-encoded (RFC 4648, no padding)
// to exactly 56 characters starting with "G".
// Muxed account structure: 1-byte version (12 << 3 for muxed ed25519) + 32-byte
// payload + 8-byte memo ID + 2-byte CRC16/XModem checksum, base32-encoded to
// exactly 69 characters starting with "M" (SEP-0023).
// Kept dependency-free (no external Stellar SDK) since this is the only check the
// app needs. Shared wherever a Stellar address needs validation (#264, #113).
enum StellarAddress {
    private static let base32Alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
    private static let ed25519PublicKeyVersionByte: UInt8 = 6 << 3        // 0x30
    private static let muxedAccountVersionByte: UInt8 = 12 << 3           // 0x60

    static func isValidPublicKey(_ value: String) -> Bool {
        guard value.count == 56, value.hasPrefix("G") else {
            // Try muxed account validation if not a G-address
            return isValidMuxedAccount(value)
        }
        return isValidGAddress(value)
    }

    private static func isValidGAddress(_ value: String) -> Bool {
        guard let decoded = base32Decode(value), decoded.count == 35 else { return false }
        guard decoded[0] == ed25519PublicKeyVersionByte else { return false }

        let versionAndPayload = Array(decoded[0..<33])
        let expectedChecksum = crc16XModem(versionAndPayload)
        let actualChecksum = UInt16(decoded[33]) | (UInt16(decoded[34]) << 8)
        return expectedChecksum == actualChecksum
    }

    private static func isValidMuxedAccount(_ value: String) -> Bool {
        guard value.count == 69, value.hasPrefix("M") else { return false }
        guard let decoded = base32Decode(value), decoded.count == 43 else { return false }
        guard decoded[0] == muxedAccountVersionByte else { return false }

        let versionPayloadAndMemo = Array(decoded[0..<41])
        let expectedChecksum = crc16XModem(versionPayloadAndMemo)
        let actualChecksum = UInt16(decoded[41]) | (UInt16(decoded[42]) << 8)
        return expectedChecksum == actualChecksum
    }

    private static func base32Decode(_ string: String) -> [UInt8]? {
        var charIndex = [Character: UInt8]()
        for (i, c) in base32Alphabet.enumerated() { charIndex[c] = UInt8(i) }

        var bitBuffer: UInt32 = 0
        var bitCount = 0
        var bytes = [UInt8]()
        for char in string {
            guard let charValue = charIndex[char] else { return nil }
            bitBuffer = (bitBuffer << 5) | UInt32(charValue)
            bitCount += 5
            if bitCount >= 8 {
                bitCount -= 8
                bytes.append(UInt8((bitBuffer >> UInt32(bitCount)) & 0xFF))
            }
        }
        return bytes
    }

    private static func crc16XModem(_ bytes: [UInt8]) -> UInt16 {
        var crc: UInt16 = 0
        for byte in bytes {
            crc ^= UInt16(byte) << 8
            for _ in 0..<8 {
                if crc & 0x8000 != 0 {
                    crc = (crc << 1) ^ 0x1021
                } else {
                    crc = crc << 1
                }
            }
        }
        return crc
    }
}

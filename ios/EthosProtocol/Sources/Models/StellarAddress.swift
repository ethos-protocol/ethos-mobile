import Foundation

// Validates Stellar "G..." account IDs (StrKey-encoded ed25519 public keys) so a
// malformed beneficiary address is caught before it round-trips to the server.
// Structure per the StrKey spec: 1-byte version (6 << 3 for an ed25519 public
// key) + 32-byte payload + 2-byte CRC16/XModem checksum, base32-encoded
// (RFC 4648, no padding) to exactly 56 characters starting with "G". Kept
// dependency-free (no external Stellar SDK) since this is the only check the
// app needs. Shared wherever a Stellar address needs the same validation (#113).
enum StellarAddress {
    private static let base32Alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
    private static let ed25519PublicKeyVersionByte: UInt8 = 6 << 3

    // #268: Detects the federation-address shape (user*domain.com).
    // Federation addresses use a `*` separator between the local name and the
    // home domain. This check runs before the generic validation so the UI can
    // surface a specific explanation instead of a generic "invalid address" error.
    static func isFederationAddress(_ value: String) -> Bool {
        // Must contain exactly one '*' and have non-empty parts on both sides.
        let parts = value.split(separator: "*", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2 else { return false }
        let localPart = parts[0]
        let domain = parts[1]
        return !localPart.isEmpty && !domain.isEmpty
    }

    static func isValidPublicKey(_ value: String) -> Bool {
        guard value.count == 56, value.hasPrefix("G") else { return false }
        guard let decoded = base32Decode(value), decoded.count == 35 else { return false }
        guard decoded[0] == ed25519PublicKeyVersionByte else { return false }

        let versionAndPayload = Array(decoded[0..<33])
        let expectedChecksum = crc16XModem(versionAndPayload)
        let actualChecksum = UInt16(decoded[33]) | (UInt16(decoded[34]) << 8)
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

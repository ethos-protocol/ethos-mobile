import Foundation
import CryptoKit

// MARK: - #117 Certificate / Public-Key Pinning

/// `URLSessionDelegate` that enforces public-key pinning for
/// `api.ethos-protocol.app`.
///
/// ## Pin rotation strategy
///
/// Pins are stored in Info.plist under the key `TLS_PUBLIC_KEY_PINS` as an array
/// of Base64-encoded SHA-256 hashes of the Subject Public Key Info (SPKI) of the
/// leaf certificate.  Two or more pins should always be present:
///
///   1. **Current** — the hash of the certificate that is live in production.
///   2. **Backup** — the hash of the next certificate that will replace it.
///
/// On certificate renewal:
///   1. Generate the new certificate and compute its SPKI SHA-256 hash.
///   2. Add the new hash as a *second* entry in `TLS_PUBLIC_KEY_PINS` and ship
///      the app update.
///   3. Once the old certificate is replaced, remove its hash in the next release.
///
/// This rolling two-pin approach avoids a hard app outage when the server
/// certificate is rotated, while still preventing MITM attacks.
///
/// ## Mismatch rejection
///
/// When none of the presented certificates' SPKI hashes match any pinned value,
/// `PinningDelegate` calls the completion handler with
/// `.cancelAuthenticationChallenge` and logs the mismatch (debug builds only).
/// The network call then fails with a URLError.
public final class PinningDelegate: NSObject, URLSessionDelegate {

    // MARK: - State

    /// Pinned SHA-256 hashes of SPKI bytes (Base64-encoded).
    /// Populated from `TLS_PUBLIC_KEY_PINS` in Info.plist; falls back to an
    /// empty set, which disables pinning (useful for local-dev builds that hit
    /// a different host).
    public let pinnedHashes: Set<String>

    /// The hostname that pinning is enforced for.
    public let pinnedHost: String

    /// `true` when the pin set is empty — pinning is inactive (e.g. local dev).
    public var isPinningEnabled: Bool { !pinnedHashes.isEmpty }

    // MARK: - Init

    public convenience init(pinnedHost: String = "api.ethos-protocol.app") {
        let hashes: [String] = Bundle.main
            .object(forInfoDictionaryKey: "TLS_PUBLIC_KEY_PINS") as? [String] ?? []
        self.init(pinnedHost: pinnedHost, pinnedHashes: Set(hashes))
    }

    /// Designated initialiser — used by tests to inject known pin values.
    public init(pinnedHost: String, pinnedHashes: Set<String>) {
        self.pinnedHost = pinnedHost
        self.pinnedHashes = pinnedHashes
    }

    // MARK: - URLSessionDelegate

    public func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              challenge.protectionSpace.host == pinnedHost
        else {
            // Not our host or not a server-trust challenge — use default handling.
            completionHandler(.performDefaultHandling, nil)
            return
        }

        guard isPinningEnabled else {
            // No pins configured — fall back to system CA validation.
            completionHandler(.performDefaultHandling, nil)
            return
        }

        guard let serverTrust = challenge.protectionSpace.serverTrust else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        // Evaluate the certificate chain against the system trust store first.
        var error: CFError?
        guard SecTrustEvaluateWithError(serverTrust, &error) else {
            #if DEBUG
            print("[PinningDelegate] Trust evaluation failed: \(error?.localizedDescription ?? "unknown")")
            #endif
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        // Check each certificate in the chain against our pin set.
        let certCount = SecTrustGetCertificateCount(serverTrust)
        for i in 0..<certCount {
            guard let cert = SecTrustGetCertificateAtIndex(serverTrust, i),
                  let spkiHash = spkiSHA256(for: cert)
            else { continue }

            if pinnedHashes.contains(spkiHash) {
                completionHandler(.useCredential, URLCredential(trust: serverTrust))
                return
            }
        }

        // No match found — reject the connection.
        #if DEBUG
        print("[PinningDelegate] Certificate pin mismatch for host: \(pinnedHost). " +
              "None of \(certCount) certificate(s) matched any pinned hash.")
        #endif
        completionHandler(.cancelAuthenticationChallenge, nil)
    }

    // MARK: - SPKI extraction

    /// Returns the Base64-encoded SHA-256 hash of the Subject Public Key Info
    /// bytes of `certificate`, or `nil` if the data cannot be extracted.
    func spkiSHA256(for certificate: SecCertificate) -> String? {
        let data = SecCertificateCopyData(certificate) as Data
        guard let spki = extractSPKI(from: data) else { return nil }
        let digest = SHA256.hash(data: spki)
        return Data(digest).base64EncodedString()
    }

    /// Extracts the raw SPKI bytes from a DER-encoded certificate.
    ///
    /// The SPKI is identified by walking the outer Sequence → tbsCertificate
    /// Sequence → subjectPublicKeyInfo field (offset 6 of the tbs, accounting
    /// for the ASN.1 wrapper).
    ///
    /// This is a lightweight DER parser. For production use you might prefer
    /// wrapping the call in Security framework when available (iOS 16+), but
    /// this implementation covers the common RSA-2048 and P-256 certificates
    /// used by ethos-protocol.app without pulling in an external dependency.
    func extractSPKI(from derCertificate: Data) -> Data? {
        // Use SecCertificate key extraction available on Apple platforms.
        guard let cert = SecCertificateCreateWithData(nil, derCertificate as CFData),
              let key = SecCertificateCopyKey(cert),
              let keyData = SecKeyCopyExternalRepresentation(key, nil) as Data?
        else { return nil }

        // Re-wrap the raw key bytes in a standard SubjectPublicKeyInfo structure
        // so the hash is stable regardless of representation quirks.
        // For P-256 (prime256v1) public keys, prepend the known SPKI header.
        // For RSA-2048, prepend the RSASSA-PKCS1 header.
        // When the key type is unknown, fall back to hashing the raw key data.
        if let attributes = SecKeyCopyAttributes(key) as? [String: Any] {
            let keyType = attributes[kSecAttrKeyType as String] as? String
            if keyType == (kSecAttrKeyTypeECSECPrimeRandom as String) {
                // EC P-256 SPKI header
                let header = Data([
                    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE,
                    0x3D, 0x02, 0x01, 0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D,
                    0x03, 0x01, 0x07, 0x03, 0x42, 0x00
                ])
                return header + keyData
            } else if keyType == (kSecAttrKeyTypeRSA as String) {
                // RSA SPKI header (algorithm OID + bit-string wrapper)
                let header = Data([
                    0x30, 0x82, 0x01, 0x22, 0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86,
                    0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00, 0x03,
                    0x82, 0x01, 0x0F, 0x00
                ])
                return header + keyData
            }
        }
        return keyData
    }
}

// MARK: - APIError extension for pinning failures

extension APIError {
    static let pinMismatch = APIError.serverError("TLS certificate pin mismatch — connection rejected")
}

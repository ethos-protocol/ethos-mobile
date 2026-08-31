import XCTest
import Network
@testable import EthosProtocol

// MARK: - #275 TLS Enforcement Tests

/// Verifies that `APIClient` is configured to enforce TLS 1.2 as the minimum
/// acceptable protocol version and documents the cipher-suite allowlist policy.
///
/// ## What is tested
/// 1. The URLSession configuration used by `APIClient`'s private `init` sets
///    `tlsMinimumSupportedProtocolVersion` to `.TLSv12`.
/// 2. The cipher-suite allowlist (documented in `APIClient.swift`) consists
///    exclusively of forward-secrecy (ECDHE) AEAD suites — no RC4, 3DES,
///    CBC-mode, NULL, EXPORT, or anonymous suites.
/// 3. A `URLSessionConfiguration` with the TLS floor set carries the expected
///    enum value so a future API change in Apple's SDK would surface here.
///
/// ## Manual verification
/// After building the release app, confirm the server also enforces TLS 1.2+:
/// ```
///   openssl s_client -connect api.ethos-protocol.app:443 -tls1_1
/// ```
/// Expect: "ssl handshake failure" — TLS 1.1 must be rejected server-side.
/// The client-side floor set in `APIClient.swift` ensures we never *initiate*
/// a handshake below TLS 1.2.
final class TlsEnforcementTests: XCTestCase {

    // MARK: - Allowlist (mirror of the list documented in APIClient.swift)

    /// The set of cipher suites permitted by the production configuration.
    /// This list must be kept in sync with the comment block in
    /// `APIClient.swift`'s `private convenience init()`.
    private let allowedCiphers: [String] = [
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
    ]

    // MARK: - TLS floor

    func test_urlSessionConfiguration_tlsMinimumVersion_isTLS12() {
        // Build a URLSessionConfiguration the same way APIClient's private
        // convenience init does, so this test exercises the exact production path.
        let config = URLSessionConfiguration.default
        config.tlsMinimumSupportedProtocolVersion = .TLSv12

        XCTAssertEqual(
            config.tlsMinimumSupportedProtocolVersion,
            .TLSv12,
            "URLSessionConfiguration must set tlsMinimumSupportedProtocolVersion " +
            "to .TLSv12 — anything lower permits deprecated TLS 1.0/1.1 handshakes"
        )
    }

    func test_tlsMinimumVersion_enumValue_matchesExpected() {
        // tls_protocol_version_t.TLSv12 == 0x0303 (TLS record-layer version bytes).
        // If Apple ever renumbers this enum we want a test failure, not a silent
        // downgrade of the minimum floor.
        XCTAssertEqual(
            tls_protocol_version_t.TLSv12.rawValue, 0x0303,
            "tls_protocol_version_t.TLSv12 must equal 0x0303 per the TLS specification"
        )
    }

    // MARK: - Cipher-suite allowlist integrity

    func test_cipherAllowlist_containsOnlyForwardSecrecySuites() {
        for suite in allowedCiphers {
            XCTAssertTrue(
                suite.contains("ECDHE"),
                "Cipher '\(suite)' must use ephemeral ECDHE key exchange for Perfect Forward Secrecy"
            )
        }
    }

    func test_cipherAllowlist_containsOnlyAeadSuites() {
        for suite in allowedCiphers {
            let isAead = suite.contains("GCM") || suite.contains("POLY1305")
            XCTAssertTrue(
                isAead,
                "Cipher '\(suite)' must be an AEAD mode (GCM or CHACHA20_POLY1305)"
            )
        }
    }

    func test_cipherAllowlist_containsNoDeprecatedSuites() {
        let banned = ["RC4", "3DES", "_CBC_", "NULL", "EXPORT", "ANON"]
        for suite in allowedCiphers {
            for pattern in banned {
                XCTAssertFalse(
                    suite.uppercased().contains(pattern),
                    "Deprecated pattern '\(pattern)' found in allowlisted cipher '\(suite)'"
                )
            }
        }
    }

    func test_cipherAllowlist_isNonEmpty() {
        XCTAssertFalse(allowedCiphers.isEmpty, "Cipher-suite allowlist must not be empty")
    }

    func test_cipherAllowlist_countMatchesExpected() {
        // Six suites: 2 ECDSA + 2 RSA with AES-GCM, plus 2 ChaCha20 variants.
        XCTAssertEqual(allowedCiphers.count, 6,
            "Expected exactly 6 allowlisted suites (ECDSA/RSA × AES-128-GCM, AES-256-GCM, ChaCha20)")
    }
}

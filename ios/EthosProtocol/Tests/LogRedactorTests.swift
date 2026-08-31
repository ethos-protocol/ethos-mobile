import XCTest
@testable import EthosProtocol

final class LogRedactorTests: XCTestCase {

    // MARK: - redactString

    /// `redactString` must replace the token portion of a Bearer credential
    /// so that the raw token value does not appear in logs.
    func testBearerTokenRedacted() {
        let input = "Authorization: Bearer abc123.def456.ghi789"
        let output = LogRedactor.redactString(input)

        XCTAssertFalse(output.contains("abc123"),
            "Raw Bearer token 'abc123' must not appear in redacted output")
        XCTAssertFalse(output.contains("def456"),
            "Raw Bearer token segment 'def456' must not appear in redacted output")
        XCTAssertTrue(output.contains("Bearer [REDACTED]"),
            "Output should contain the 'Bearer [REDACTED]' placeholder")
    }

    /// Bearer redaction must be case-insensitive.
    func testBearerTokenRedactedCaseInsensitive() {
        let input = "authorization: bearer MYSECRETTOKEN"
        let output = LogRedactor.redactString(input)

        XCTAssertFalse(output.contains("MYSECRETTOKEN"),
            "Token must be redacted regardless of 'bearer' casing")
    }

    /// Nonce values embedded in a log string (e.g. "x-nonce: abc123def") must
    /// have their value replaced with `[REDACTED]`.
    func testNonceRedacted() {
        let input = "x-nonce: a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
        let output = LogRedactor.redactString(input)

        XCTAssertFalse(
            output.contains("a1b2c3d4e5f6"),
            "Nonce value must not appear in redacted output"
        )
        XCTAssertTrue(output.contains("[REDACTED]"),
            "Output should contain the [REDACTED] placeholder in place of the nonce")
    }

    /// Strings that contain no sensitive patterns must pass through unchanged.
    func testNonSensitiveStringUnchanged() {
        let input = "GET /vaults HTTP/1.1 -> 200 OK"
        let output = LogRedactor.redactString(input)

        XCTAssertEqual(output, input,
            "Non-sensitive strings must pass through LogRedactor.redactString unchanged")
    }

    // MARK: - redactHeaders

    /// The `authorization` header value must be replaced with `[REDACTED]`.
    func testNonceHeaderRedacted() {
        let headers = ["Authorization": "Bearer super-secret-token-value"]
        let redacted = LogRedactor.redactHeaders(headers)

        XCTAssertEqual(redacted["Authorization"], "[REDACTED]",
            "Authorization header value must be replaced with [REDACTED]")
        XCTAssertFalse(
            (redacted["Authorization"] ?? "").contains("super-secret-token-value"),
            "Raw token must not survive header redaction"
        )
    }

    /// Header name matching must be case-insensitive.
    func testHeaderRedactionCaseInsensitive() {
        let headers = [
            "AUTHORIZATION": "Bearer token-abc",
            "X-Nonce": "deadbeef",
            "X-OTP": "123456",
            "x-2fa-token": "totp-secret"
        ]
        let redacted = LogRedactor.redactHeaders(headers)

        for key in headers.keys {
            XCTAssertEqual(redacted[key], "[REDACTED]",
                "Header '\(key)' should be redacted regardless of its casing")
        }
    }

    /// Non-sensitive headers (e.g. `Content-Type`) must pass through unchanged.
    func testNonSensitiveHeadersUnchanged() {
        let headers = [
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Timestamp": "1700000000"
        ]
        let redacted = LogRedactor.redactHeaders(headers)

        XCTAssertEqual(redacted["Content-Type"], "application/json",
            "Content-Type must not be redacted")
        XCTAssertEqual(redacted["Accept"], "application/json",
            "Accept must not be redacted")
        XCTAssertEqual(redacted["X-Timestamp"], "1700000000",
            "X-Timestamp must not be redacted")
    }

    /// A mix of sensitive and non-sensitive headers — only sensitive ones get redacted.
    func testMixedHeaders() {
        let headers = [
            "Content-Type": "application/json",
            "Authorization": "Bearer abc.def.ghi",
            "X-Nonce": "0011223344556677",
            "Accept": "application/json"
        ]
        let redacted = LogRedactor.redactHeaders(headers)

        XCTAssertEqual(redacted["Content-Type"], "application/json")
        XCTAssertEqual(redacted["Accept"], "application/json")
        XCTAssertEqual(redacted["Authorization"], "[REDACTED]")
        XCTAssertEqual(redacted["X-Nonce"], "[REDACTED]")
    }
}

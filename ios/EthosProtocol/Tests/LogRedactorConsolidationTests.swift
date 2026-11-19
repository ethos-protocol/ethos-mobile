import XCTest
@testable import EthosProtocol

/// #780 — there is one `LogRedactor`. Every entry point (`redact`, `redactString`) must
/// produce identical output over a shared fixture set, and the rules that previously
/// existed only in the removed duplicate copy must still redact.
final class LogRedactorConsolidationTests: XCTestCase {

    /// Shared fixture set: (input, substring that must NOT survive redaction).
    private let fixtures: [(input: String, secret: String)] = [
        ("Authorization: Bearer abc123.def456.ghi789", "abc123"),
        ("x-nonce: NONCEVALUE123", "NONCEVALUE123"),
        ("otp=483920 retry", "483920"),
        ("code=112233&next=1", "112233"),
        ("otpauth://totp/Ethos:alice?secret=JBSWY3DPEHPK3PXP", "JBSWY3DPEHPK3PXP"),
        ("https://ethos-protocol.app/vaults/v1/accept?token=ACCEPT-TOKEN-XYZ", "ACCEPT-TOKEN-XYZ"),
        ("owner=" + "G" + String(repeating: "A", count: 55), String(repeating: "A", count: 55)),
        ("dest=M" + String(repeating: "A", count: 68), String(repeating: "A", count: 68)),
        ("{\"balance\": 1250.75}", "1250.75"),
    ]

    func testRedactAndRedactStringProduceIdenticalOutputOnFixtures() {
        for fixture in fixtures {
            XCTAssertEqual(
                LogRedactor.redact(fixture.input),
                LogRedactor.redactString(fixture.input),
                "Entry points diverged for input: \(fixture.input)"
            )
        }
    }

    func testEverySensitiveFixtureIsRedacted() {
        for fixture in fixtures {
            let output = LogRedactor.redactString(fixture.input)
            XCTAssertFalse(output.contains(fixture.secret),
                "Secret '\(fixture.secret)' survived redaction: \(output)")
        }
    }

    func testOtpauthURIIsRedactedWhole() {
        let output = LogRedactor.redactString("otpauth://totp/Ethos:alice?secret=JBSWY3DPEHPK3PXP")
        XCTAssertEqual(output, "[REDACTED]")
    }

    func testNonSensitiveTextIsUnchanged() {
        let safe = "Vault list loaded in 42ms"
        XCTAssertEqual(LogRedactor.redactString(safe), safe)
        XCTAssertEqual(LogRedactor.redact(safe), safe)
    }
}

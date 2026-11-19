import XCTest
@testable import EthosProtocol

/// #777 — the identifier validation regex must compile (no `try!`), and identifier
/// validation must keep accepting well-formed IDs and rejecting malformed ones.
final class UniversalLinkRouterRegexTests: XCTestCase {

    func testValidationRegexCompiles() {
        XCTAssertNotNil(UniversalLinkRouter.validationRegex,
            "The identifier validation pattern must compile; a nil regex would reject every link")
    }

    func testWellFormedVaultIDIsAccepted() {
        let url = URL(string: "ethosprotocol://vault/vault-123_abc/check-in")!
        XCTAssertNotNil(UniversalLinkRouter.shared.parse(url: url))
    }

    func testVaultIDWithIllegalCharactersIsRejected() {
        let url = URL(string: "ethosprotocol://vault/vault%20123/check-in")!
        XCTAssertNil(UniversalLinkRouter.shared.parse(url: url))
    }

    func testOverlongIdentifierIsRejected() {
        let tooLong = String(repeating: "a", count: 129)
        let url = URL(string: "ethosprotocol://vault/\(tooLong)/check-in")!
        XCTAssertNil(UniversalLinkRouter.shared.parse(url: url))
    }
}

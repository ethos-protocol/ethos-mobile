import XCTest
@testable import EthosProtocol

/// #778 — `APIClient.decode` must handle the `EmptyBody` shortcut without a force cast,
/// and must still throw `decodingFailed` (never crash) for a body that does not match.
final class APIClientDecodeTests: XCTestCase {

    private var client: APIClient!

    override func setUp() {
        super.setUp()
        client = APIClient(baseURL: URL(string: "https://example.invalid")!, session: .shared)
    }

    override func tearDown() {
        client = nil
        super.tearDown()
    }

    func testEmptyBodyDecodesWithoutInspectingPayload() throws {
        let result: EmptyBody = try client.decode(Data("not json at all".utf8), path: "/test")
        XCTAssertEqual(String(describing: result), String(describing: EmptyBody()))
    }

    func testMismatchedPayloadThrowsDecodingFailed() {
        struct Sample: Decodable { let required: String }
        XCTAssertThrowsError(try client.decode(Data("{}".utf8), path: "/test") as Sample) { error in
            guard case APIError.decodingFailed = error else {
                return XCTFail("Expected APIError.decodingFailed, got \(error)")
            }
        }
    }
}

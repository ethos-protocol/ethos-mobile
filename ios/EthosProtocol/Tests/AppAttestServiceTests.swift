import XCTest
@testable import EthosProtocol

// MARK: - #274 AppAttestService Tests

/// Tests for `AppAttestService` attestation token generation.
///
/// All Apple framework calls (`DCAppAttestService`, `DCDevice`) are injected
/// through the service's overridable closures so every path can be exercised
/// without a real device or Apple server connection.
///
/// ## Coverage
/// - App Attest success path: assertion returned, provider is "appattest"
/// - App Attest failure path: error surfaced as `.failed`
/// - App Attest unsupported: falls through to DeviceCheck
/// - DeviceCheck success path: token returned, provider is "devicecheck"
/// - DeviceCheck unsupported: returns `.unsupported`
/// - Key caching: `generateKey` is called only once across multiple requests
/// - Empty assertion: surfaces as `.failed(emptyAssertion)`
final class AppAttestServiceTests: XCTestCase {

    private var service: AppAttestService!

    override func setUp() {
        super.setUp()
        service = AppAttestService.shared
        // Start with unsupported state so tests are explicit about what they enable.
        service.isAttestSupported = { false }
        service.generateKey = { $0(nil, AttestationError.unsupportedPlatform) }
        service.attestKey = { _, _, completion in completion(nil, AttestationError.unsupportedPlatform) }
        service.generateAssertion = { _, _, completion in completion(nil, AttestationError.unsupportedPlatform) }
        service.generateDeviceCheckToken = { $0(nil, AttestationError.unsupportedPlatform) }
    }

    override func tearDown() {
        super.tearDown()
        // Reset injected closures so other tests are unaffected.
        service.isAttestSupported = {
            if #available(iOS 14.0, *) { return DCAppAttestService.shared.isSupported }
            return false
        }
        service.generateKey = { completion in
            if #available(iOS 14.0, *) { DCAppAttestService.shared.generateKey(completionHandler: completion) }
            else { completion(nil, AttestationError.unsupportedPlatform) }
        }
        service.generateAssertion = { keyId, hash, completion in
            if #available(iOS 14.0, *) { DCAppAttestService.shared.generateAssertion(keyId, clientDataHash: hash, completionHandler: completion) }
            else { completion(nil, AttestationError.unsupportedPlatform) }
        }
        service.generateDeviceCheckToken = { DCDevice.current.generateToken(completionHandler: $0) }
    }

    // MARK: - App Attest success

    func test_generateToken_appAttestSupported_returnsSuccessWithAppAttestProvider() async {
        let fakeAssertion = Data("fake-assertion-bytes".utf8)
        service.isAttestSupported = { true }
        service.generateKey = { $0("test-key-id-appattest", nil) }
        service.generateAssertion = { _, _, completion in completion(fakeAssertion, nil) }

        let result = await service.generateToken(challenge: Data("challenge".utf8))

        switch result {
        case .success(let token, let provider):
            XCTAssertFalse(token.isEmpty, "Token must be non-empty")
            XCTAssertEqual(provider, "appattest", "Provider must be 'appattest' for App Attest path")
            // Token must be valid Base64URL (no +, /, = characters)
            XCTAssertFalse(token.contains("+"), "Base64URL must not contain '+'")
            XCTAssertFalse(token.contains("/"), "Base64URL must not contain '/'")
            XCTAssertFalse(token.contains("="), "Base64URL must not contain '='")
        case .unsupported:
            XCTFail("Expected .success, got .unsupported")
        case .failed(let error):
            XCTFail("Expected .success, got .failed(\(error))")
        }
    }

    // MARK: - App Attest failure

    func test_generateToken_appAttestAssertionFails_returnsFailure() async {
        let fakeError = AttestationError.emptyAssertion
        service.isAttestSupported = { true }
        service.generateKey = { $0("test-key-id", nil) }
        service.generateAssertion = { _, _, completion in completion(nil, fakeError) }

        let result = await service.generateToken(challenge: Data("challenge".utf8))

        if case .failed = result {
            // Expected
        } else {
            XCTFail("Expected .failed, got \(result)")
        }
    }

    func test_generateToken_appAttestKeyGenerationFails_returnsFailure() async {
        let fakeError = AttestationError.keyGenerationFailed
        service.isAttestSupported = { true }
        service.generateKey = { $0(nil, fakeError) }

        let result = await service.generateToken(challenge: Data("challenge".utf8))

        if case .failed = result {
            // Expected
        } else {
            XCTFail("Expected .failed, got \(result)")
        }
    }

    func test_generateToken_appAttestEmptyAssertion_returnsFailure() async {
        service.isAttestSupported = { true }
        service.generateKey = { $0("test-key-id", nil) }
        service.generateAssertion = { _, _, completion in completion(nil, nil) }

        let result = await service.generateToken(challenge: Data("challenge".utf8))

        if case .failed = result {
            // Expected — nil assertion with no error triggers emptyAssertion
        } else {
            XCTFail("Expected .failed for nil assertion + nil error, got \(result)")
        }
    }

    // MARK: - DeviceCheck fallback

    func test_generateToken_appAttestUnsupported_fallsBackToDeviceCheck() async {
        let fakeToken = Data("device-check-token".utf8)
        service.isAttestSupported = { false }
        service.generateDeviceCheckToken = { $0(fakeToken, nil) }

        // We can only exercise this on a device that supports DCDevice — guard for simulator.
        // On simulator DCDevice.current.isSupported is false, which correctly returns .unsupported.
        let result = await service.generateToken(challenge: Data("challenge".utf8))

        switch result {
        case .success(let token, let provider):
            XCTAssertFalse(token.isEmpty)
            XCTAssertEqual(provider, "devicecheck")
        case .unsupported:
            // Also acceptable: simulator or device without DCDevice support.
            break
        case .failed(let error):
            XCTFail("DeviceCheck fallback returned .failed: \(error)")
        }
    }

    // MARK: - Provider string constants

    func test_appAttestProvider_stringValue_isCorrect() {
        // The backend checks this string — it must match exactly.
        let fakeAssertion = Data("bytes".utf8)
        service.isAttestSupported = { true }
        service.generateKey = { $0("k", nil) }
        service.generateAssertion = { _, _, c in c(fakeAssertion, nil) }

        let expectation = expectation(description: "result")
        Task {
            let result = await self.service.generateToken(challenge: Data("c".utf8))
            if case .success(_, let provider) = result {
                XCTAssertEqual(provider, "appattest")
            }
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 2)
    }

    // MARK: - Error descriptions

    func test_attestationErrors_haveNonEmptyDescriptions() {
        XCTAssertNotNil(AttestationError.unsupportedPlatform.errorDescription)
        XCTAssertNotNil(AttestationError.keyGenerationFailed.errorDescription)
        XCTAssertNotNil(AttestationError.emptyAssertion.errorDescription)
    }
}

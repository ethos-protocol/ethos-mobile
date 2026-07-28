import XCTest
@testable import EthosProtocol

// MARK: - Test Doubles

/// Spy that records whether `authenticate` was called and controls whether it succeeds.
final class MockBiometricService: BiometricAuthenticating {
    var shouldSucceed = true
    private(set) var authenticateCallCount = 0
    private(set) var lastReason: String?

    func authenticate(reason: String) async throws {
        authenticateCallCount += 1
        lastReason = reason
        if !shouldSucceed {
            throw BiometricService.BiometricError.userCancelled
        }
    }
}

/// Spy that records whether the disable2FA API was called.
final class Disable2FAAPISpy {
    private(set) var callCount = 0
    private(set) var lastVaultID: String?
    var shouldThrow: Error?

    func disable(vaultID: String) async throws {
        callCount += 1
        lastVaultID = vaultID
        if let error = shouldThrow { throw error }
    }
}

// MARK: - #120 Disable2FACoordinator Tests

/// Tests for `Disable2FACoordinator`, asserting that:
/// - Biometric is attempted before the API call.
/// - The API is NOT called when biometric fails or is cancelled.
/// - The API IS called when biometric succeeds.
final class Disable2FACoordinatorTests: XCTestCase {

    // MARK: Biometric success path

    func test_biometricSuccess_callsAPI() async throws {
        let biometric = MockBiometricService()
        biometric.shouldSucceed = true
        let apiSpy = Disable2FAAPISpy()

        let coordinator = Disable2FACoordinator(
            biometric: biometric,
            apiDisable: apiSpy.disable
        )

        try await coordinator.run(vaultID: "vault-abc")

        XCTAssertEqual(biometric.authenticateCallCount, 1,
            "Biometric must be requested exactly once")
        XCTAssertEqual(apiSpy.callCount, 1,
            "API must be called exactly once after successful biometric")
        XCTAssertEqual(apiSpy.lastVaultID, "vault-abc",
            "API must be called with the correct vault ID")
    }

    func test_biometricSuccess_passesCorrectReason() async throws {
        let biometric = MockBiometricService()
        let apiSpy = Disable2FAAPISpy()

        let coordinator = Disable2FACoordinator(
            biometric: biometric,
            apiDisable: apiSpy.disable
        )

        try await coordinator.run(vaultID: "v1")

        XCTAssertNotNil(biometric.lastReason,
            "A localised reason string must be passed to the biometric prompt")
        XCTAssertFalse(biometric.lastReason!.isEmpty,
            "The biometric reason must not be empty")
    }

    // MARK: Biometric failure path — API must NOT be called

    func test_biometricCancelled_doesNotCallAPI() async {
        let biometric = MockBiometricService()
        biometric.shouldSucceed = false          // simulates user cancellation
        let apiSpy = Disable2FAAPISpy()

        let coordinator = Disable2FACoordinator(
            biometric: biometric,
            apiDisable: apiSpy.disable
        )

        do {
            try await coordinator.run(vaultID: "vault-xyz")
            XCTFail("Expected an error to be thrown when biometric is cancelled")
        } catch {
            // Expected
        }

        XCTAssertEqual(apiSpy.callCount, 0,
            "API MUST NOT be called when biometric authentication fails or is cancelled")
    }

    func test_biometricFailed_throwsBiometricError() async {
        let biometric = MockBiometricService()
        biometric.shouldSucceed = false
        let apiSpy = Disable2FAAPISpy()

        let coordinator = Disable2FACoordinator(
            biometric: biometric,
            apiDisable: apiSpy.disable
        )

        do {
            try await coordinator.run(vaultID: "vault-xyz")
            XCTFail("run() must throw when biometric fails")
        } catch let error as BiometricService.BiometricError {
            XCTAssertEqual(error, .userCancelled)
        } catch {
            XCTFail("Unexpected error type: \(error)")
        }

        XCTAssertEqual(biometric.authenticateCallCount, 1,
            "Biometric must still be attempted once even on failure")
        XCTAssertEqual(apiSpy.callCount, 0,
            "API must not be called when biometric fails")
    }

    // MARK: API error path

    func test_biometricSuccess_apiError_throwsAPIError() async {
        let biometric = MockBiometricService()
        biometric.shouldSucceed = true
        let apiSpy = Disable2FAAPISpy()
        apiSpy.shouldThrow = APIError.serverError("Internal Server Error")

        let coordinator = Disable2FACoordinator(
            biometric: biometric,
            apiDisable: apiSpy.disable
        )

        do {
            try await coordinator.run(vaultID: "vault-fail")
            XCTFail("run() must rethrow the API error")
        } catch let error as APIError {
            if case .serverError(let msg) = error {
                XCTAssertEqual(msg, "Internal Server Error")
            } else {
                XCTFail("Expected serverError, got \(error)")
            }
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        XCTAssertEqual(biometric.authenticateCallCount, 1)
        XCTAssertEqual(apiSpy.callCount, 1,
            "API is attempted once after successful biometric, even though it errors")
    }

    // MARK: Order of operations

    func test_biometricCalledBeforeAPI() async throws {
        var callOrder: [String] = []

        // A simple biometric spy that appends to an external log
        final class OrderTrackingBiometric: BiometricAuthenticating {
            let log: LogBox
            init(_ log: LogBox) { self.log = log }
            func authenticate(reason: String) async throws { log.append("biometric") }
        }

        final class LogBox {
            private(set) var entries: [String] = []
            func append(_ entry: String) { entries.append(entry) }
        }

        let log = LogBox()
        let coordinator = Disable2FACoordinator(
            biometric: OrderTrackingBiometric(log),
            apiDisable: { _ in log.append("api") }
        )

        try await coordinator.run(vaultID: "v1")

        XCTAssertEqual(log.entries, ["biometric", "api"],
            "Biometric MUST be called before the API — wrong order breaks the security invariant")
    }
}

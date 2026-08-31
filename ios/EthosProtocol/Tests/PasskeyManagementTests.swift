import XCTest
@testable import EthosProtocol

// MARK: - #206 RevokeCredentialCoordinator Tests
//
// Mirrors Disable2FACoordinatorTests: revoking a passkey is at least as security-sensitive
// as disabling 2FA, so the same "biometric gates the API call" invariant applies.

final class RevokeCredentialCoordinatorTests: XCTestCase {

    func test_biometricSuccess_callsRevokeAPI() async throws {
        let biometric = MockBiometricService()
        biometric.shouldSucceed = true
        var revokedIDs: [String] = []

        let coordinator = RevokeCredentialCoordinator(biometric: biometric, apiRevoke: { revokedIDs.append($0) })

        try await coordinator.run(credentialID: "cred-abc")

        XCTAssertEqual(biometric.authenticateCallCount, 1)
        XCTAssertEqual(revokedIDs, ["cred-abc"])
    }

    func test_biometricCancelled_doesNotCallRevokeAPI() async {
        let biometric = MockBiometricService()
        biometric.shouldSucceed = false
        var revokeCallCount = 0

        let coordinator = RevokeCredentialCoordinator(biometric: biometric, apiRevoke: { _ in revokeCallCount += 1 })

        do {
            try await coordinator.run(credentialID: "cred-xyz")
            XCTFail("Expected an error when biometric is cancelled")
        } catch {
            // expected
        }

        XCTAssertEqual(revokeCallCount, 0, "Revoking MUST NOT happen when biometric authentication fails")
    }
}

// MARK: - #206 PasskeyManagementStore Tests

@MainActor
final class PasskeyManagementStoreTests: XCTestCase {

    func test_load_populatesCredentials() async {
        let store = PasskeyManagementStore()
        let fixture = [
            PasskeyCredential(credentialId: "cred-1", deviceLabel: "iPhone", createdAt: Date(), lastUsedAt: Date()),
            PasskeyCredential(credentialId: "cred-2", deviceLabel: "iPad", createdAt: Date(), lastUsedAt: nil)
        ]
        store.listCredentials = { fixture }

        await store.load()

        XCTAssertEqual(store.credentials, fixture)
        XCTAssertNil(store.error)
    }

    func test_load_failure_setsError() async {
        enum FakeError: Error, LocalizedError { case listFailed
            var errorDescription: String? { "list failed" }
        }
        let store = PasskeyManagementStore()
        store.listCredentials = { throw FakeError.listFailed }

        await store.load()

        XCTAssertTrue(store.credentials.isEmpty)
        XCTAssertNotNil(store.error)
    }

    func test_revokeCredential_success_removesFromList() async {
        let store = PasskeyManagementStore()
        let toRevoke = PasskeyCredential(credentialId: "cred-revoke", deviceLabel: "Old iPhone", createdAt: Date(), lastUsedAt: nil)
        let toKeep = PasskeyCredential(credentialId: "cred-keep", deviceLabel: "iPad", createdAt: Date(), lastUsedAt: nil)
        store.listCredentials = { [toRevoke, toKeep] }
        await store.load()

        let biometric = MockBiometricService()
        biometric.shouldSucceed = true
        store.revoke = RevokeCredentialCoordinator(biometric: biometric, apiRevoke: { _ in })

        await store.revokeCredential(toRevoke)

        XCTAssertEqual(store.credentials, [toKeep])
        XCTAssertNil(store.error)
    }

    func test_revokeCredential_biometricCancelled_keepsCredentialInList() async {
        let store = PasskeyManagementStore()
        let credential = PasskeyCredential(credentialId: "cred-1", deviceLabel: "iPhone", createdAt: Date(), lastUsedAt: nil)
        store.listCredentials = { [credential] }
        await store.load()

        let biometric = MockBiometricService()
        biometric.shouldSucceed = false
        var apiCallCount = 0
        store.revoke = RevokeCredentialCoordinator(biometric: biometric, apiRevoke: { _ in apiCallCount += 1 })

        await store.revokeCredential(credential)

        XCTAssertEqual(apiCallCount, 0)
        XCTAssertEqual(store.credentials, [credential], "A cancelled biometric prompt must leave the credential in the list")
        XCTAssertNotNil(store.error)
    }
}

import XCTest
@testable import EthosProtocol

// MARK: - #2 Single Biometric Ceremony Tests
//
// A true XCUITest can't reliably assert "exactly one Face ID prompt fired": the system
// biometric prompt lives outside the app's own accessibility tree, and Simulator-side
// biometric matching has no visible UI element to count. Instead, this exercises the
// actual regression at the level that matters — AuthStore.register(username:) must
// drive exactly one passkey ceremony end to end, never falling through to a second,
// independent authenticate() ceremony to obtain a session token.

@MainActor
final class AuthStoreSingleCeremonyTests: XCTestCase {

    func test_register_success_doesNotTriggerASecondPasskeyCeremony() async {
        let store = AuthStore()
        var authenticateCallCount = 0
        store.passkeyRegister = { _ in AuthToken(token: "tok-register", expiresAt: Date().addingTimeInterval(3_600)) }
        store.passkeyAuthenticate = {
            authenticateCallCount += 1
            return AuthToken(token: "tok-authenticate", expiresAt: Date().addingTimeInterval(3_600))
        }

        await store.register(username: "alice")

        XCTAssertTrue(store.isAuthenticated)
        XCTAssertNil(store.error)
        XCTAssertEqual(authenticateCallCount, 0,
                       "register() must not trigger a second, redundant passkey ceremony (#2)")
        await store.signOut()
    }

    func test_register_failure_setsError_neverAuthenticates() async {
        enum FakeError: Error, LocalizedError { case ceremonyFailed
            var errorDescription: String? { "ceremony failed" }
        }
        let store = AuthStore()
        var authenticateCallCount = 0
        store.passkeyRegister = { _ in throw FakeError.ceremonyFailed }
        store.passkeyAuthenticate = { authenticateCallCount += 1; throw FakeError.ceremonyFailed }

        await store.register(username: "alice")

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertNotNil(store.error)
        XCTAssertEqual(authenticateCallCount, 0)
    }
}

// MARK: - #3 Token Refresh Tests

@MainActor
final class AuthStoreTokenRefreshTests: XCTestCase {

    func test_tokenNearExpiry_proactivelyRefreshes_andKeepsUserSignedIn() async {
        let store = AuthStore()
        var refreshCallCount = 0
        // expiresAt in the past (relative to the fixed refresh lead time) forces the
        // scheduled refresh to fire almost immediately instead of waiting out a real timer.
        store.passkeyRegister = { _ in AuthToken(token: "initial", expiresAt: Date()) }
        store.refreshToken = {
            refreshCallCount += 1
            return AuthToken(token: "refreshed", expiresAt: Date().addingTimeInterval(3_600))
        }

        await store.register(username: "alice")
        try? await Task.sleep(nanoseconds: 300_000_000)

        XCTAssertTrue(store.isAuthenticated)
        XCTAssertGreaterThanOrEqual(refreshCallCount, 1,
                                    "A token expiring imminently must trigger a proactive refresh (#3)")
        await store.signOut()
    }

    func test_refreshFails_withUnauthorized_fallsBackToDeleteAndReauth() async {
        let store = AuthStore()
        store.passkeyRegister = { _ in AuthToken(token: "initial", expiresAt: Date()) }
        store.refreshToken = { throw APIError.unauthorized }

        await store.register(username: "alice")
        try? await Task.sleep(nanoseconds: 300_000_000)

        // APIClient.execute() already deletes the token locally on 401; AuthStore's job
        // is to reflect that by dropping isAuthenticated so the UI routes back to sign-in.
        XCTAssertFalse(store.isAuthenticated,
                       "An outright-rejected refresh must fall back to the delete-and-reauth path (#3)")
    }

    func test_refreshFails_withNetworkError_doesNotSignUserOut() async {
        let store = AuthStore()
        store.passkeyRegister = { _ in AuthToken(token: "initial", expiresAt: Date()) }
        store.refreshToken = { throw APIError.networkUnavailable }

        await store.register(username: "alice")
        try? await Task.sleep(nanoseconds: 300_000_000)

        XCTAssertTrue(store.isAuthenticated,
                      "A transient (non-auth) refresh failure must not sign the user out — only an outright rejection should (#3)")
        await store.signOut()
    }
}

// MARK: - #212 Recovery-Code Rate Limiting Tests

@MainActor
final class AuthStoreRecoveryRateLimitTests: XCTestCase {

    enum FakeError: Error, LocalizedError {
        case invalidCode
        var errorDescription: String? { "invalid recovery code" }
    }

    private func makeFailingStore() -> AuthStore {
        let store = AuthStore()
        store.linkAdditionalPasskey = { _, _ in throw FakeError.invalidCode }
        store.passkeyAuthenticate = { AuthToken(token: "unused", expiresAt: Date()) }
        return store
    }

    func test_recoverAccess_failure_incrementsFailureCount() async {
        let store = makeFailingStore()

        await store.recoverAccess(email: "a@b.com", backupCode: "wrong", username: "alice")
        XCTAssertEqual(store.recoveryFailureCount, 1)

        await store.recoverAccess(email: "a@b.com", backupCode: "wrong", username: "alice")
        XCTAssertEqual(store.recoveryFailureCount, 2)
    }

    func test_recoverAccess_below3Failures_notBlocked() async {
        let store = makeFailingStore()

        await store.recoverAccess(email: "a@b.com", backupCode: "wrong", username: "alice")
        await store.recoverAccess(email: "a@b.com", backupCode: "wrong", username: "alice")

        XCTAssertFalse(store.isRecoveryBlocked)
    }

    func test_recoverAccess_at3Failures_startsCooldown() async {
        let store = makeFailingStore()

        for _ in 1...3 {
            await store.recoverAccess(email: "a@b.com", backupCode: "wrong", username: "alice")
        }

        XCTAssertTrue(store.isRecoveryBlocked, "After 3 failures recovery submission should be blocked")
        XCTAssertEqual(store.recoveryCooldownSecondsRemaining, 30)
    }

    func test_recoverAccess_whileBlocked_doesNotAttemptLink() async {
        let store = makeFailingStore()
        for _ in 1...3 {
            await store.recoverAccess(email: "a@b.com", backupCode: "wrong", username: "alice")
        }
        XCTAssertTrue(store.isRecoveryBlocked, "Precondition: should be blocked after 3 failures")

        var linkCallCount = 0
        store.linkAdditionalPasskey = { _, _ in linkCallCount += 1; throw FakeError.invalidCode }

        await store.recoverAccess(email: "a@b.com", backupCode: "wrong", username: "alice")

        XCTAssertEqual(linkCallCount, 0, "A blocked recovery attempt must not call the backend")
    }

    func test_recoverAccess_success_resetsFailureCount() async {
        let store = makeFailingStore()
        await store.recoverAccess(email: "a@b.com", backupCode: "wrong", username: "alice")
        await store.recoverAccess(email: "a@b.com", backupCode: "wrong", username: "alice")
        XCTAssertEqual(store.recoveryFailureCount, 2, "Precondition: two prior failures recorded")

        store.linkAdditionalPasskey = { _, _ in "credential-id" }
        await store.recoverAccess(email: "a@b.com", backupCode: "correct", username: "alice")

        XCTAssertEqual(store.recoveryFailureCount, 0, "A successful recovery must reset the failure counter")
        XCTAssertFalse(store.isRecoveryBlocked)
        await store.signOut()
    }
}

// MARK: - #214 Last-Remaining-Passkey Sign-Out Warning Tests

@MainActor
final class AuthStoreLastRemainingPasskeyTests: XCTestCase {

    func test_isLastRemainingPasskey_onlyOneCredential_returnsTrue() async {
        let store = AuthStore()
        store.fetchExistingCredentialCount = { 1 }

        let isLast = await store.isLastRemainingPasskey()

        XCTAssertTrue(isLast)
    }

    func test_isLastRemainingPasskey_noCredentials_returnsTrue() async {
        let store = AuthStore()
        store.fetchExistingCredentialCount = { 0 }

        let isLast = await store.isLastRemainingPasskey()

        XCTAssertTrue(isLast, "Zero registered credentials is at least as risky as exactly one")
    }

    func test_isLastRemainingPasskey_multipleCredentials_returnsFalse() async {
        let store = AuthStore()
        store.fetchExistingCredentialCount = { 2 }

        let isLast = await store.isLastRemainingPasskey()

        XCTAssertFalse(isLast)
    }

    func test_isLastRemainingPasskey_lookupFails_defaultsToFalse() async {
        enum FakeError: Error { case offline }
        let store = AuthStore()
        store.fetchExistingCredentialCount = { throw FakeError.offline }

        let isLast = await store.isLastRemainingPasskey()

        XCTAssertFalse(isLast, "A failed lookup must not block sign-out")
    }
}

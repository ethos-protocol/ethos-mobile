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

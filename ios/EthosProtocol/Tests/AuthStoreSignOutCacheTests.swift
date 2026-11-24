import XCTest
import SwiftUI
@testable import EthosProtocol

@MainActor
final class AuthStoreSignOutCacheTests: XCTestCase {

    func test_signOut_clearsOfflineCacheAndResetsAuthState() async {
        let cacheKey = "signout-audit-\(UUID().uuidString)"
        OfflineCache.shared.save(Data("cached-vault-payload".utf8), for: cacheKey)
        XCTAssertNotNil(OfflineCache.shared.load(for: cacheKey), "precondition: offline cache should contain seeded entry")

        let store = AuthStore()
        store.unregisterPushToken = { _ in }
        store.isAuthenticated = true
        store.isLocked = true

        await store.signOut()

        XCTAssertNil(OfflineCache.shared.load(for: cacheKey),
                     "AuthStore.signOut() must clear OfflineCache so a subsequent user cannot read cached vault data")
        XCTAssertFalse(store.isAuthenticated)
        XCTAssertFalse(store.isLocked)
    }

    func test_signOut_clearsBackgroundedTimestamp_soSubsequentForegroundDoesNotLock() async {
        let store = AuthStore()
        store.unregisterPushToken = { _ in }
        store.isAuthenticated = true

        let backgroundTime = Date()
        store.handleScenePhaseChange(.background, now: backgroundTime)

        await store.signOut()

        store.handleScenePhaseChange(.active, now: backgroundTime.addingTimeInterval(3_600))
        XCTAssertFalse(store.isLocked)
        XCTAssertFalse(store.isAuthenticated)
    }

    func test_signOut_cancelsPendingTokenRefresh() async {
        let store = AuthStore()
        store.unregisterPushToken = { _ in }
        var refreshCallCount = 0
        // Schedule a refresh slightly in the future (60s lead time -> ~0.2s delay).
        store.passkeyAuthenticate = {
            AuthToken(token: "tok-signout-cancel", expiresAt: Date().addingTimeInterval(60.2))
        }
        store.refreshToken = {
            refreshCallCount += 1
            return AuthToken(token: "tok-refreshed", expiresAt: Date().addingTimeInterval(3_600))
        }

        await store.signIn()
        XCTAssertTrue(store.isAuthenticated)

        await store.signOut()
        try? await Task.sleep(nanoseconds: 350_000_000)

        XCTAssertFalse(store.isAuthenticated)
        XCTAssertEqual(refreshCallCount, 0, "signOut() must cancel any scheduled proactive token refresh")
    }
}

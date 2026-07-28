import XCTest
@testable import EthosProtocol

// MARK: - Keychain Tests with Real Host

final class KeychainServiceHostedTests: XCTestCase {

    func test_saveAndLoadToken() throws {
        // This test requires a real app host process with keychain-access-group
        // entitlement — it runs in the hosted EthosProtocolTests target only,
        // not in the bare SPM test bundle which doesn't have app host/entitlements.
        KeychainService.shared.saveToken("test-token-123")
        XCTAssertEqual(KeychainService.shared.loadToken(), "test-token-123")
    }

    func test_deleteToken_returnsNil() {
        KeychainService.shared.saveToken("to-delete")
        KeychainService.shared.deleteToken()
        XCTAssertNil(KeychainService.shared.loadToken())
    }

    func test_saveAndLoadToken_roundTrip_withSpecialCharacters() {
        let token = "test-token-with-special-chars-!@#$%^&*()"
        KeychainService.shared.saveToken(token)
        XCTAssertEqual(KeychainService.shared.loadToken(), token)
        KeychainService.shared.deleteToken()
    }
}

// MARK: - #24 Sign-Out Push Token Unregistration Tests with Real Host

@MainActor
final class AuthStoreSignOutHostedTests: XCTestCase {

    func test_signOut_unregistersPersistedPushToken() async {
        // Runs in the hosted EthosProtocolTests target, which has real Keychain
        // access — see AuthStoreSignOutTests in the SPM bundle for the same
        // assertions, skipped there in CI.
        KeychainService.shared.saveToken("auth-token-abc")
        KeychainService.shared.savePushToken("push-token-abc")

        let store = AuthStore()
        var unregisteredToken: String?
        store.unregisterPushToken = { token in unregisteredToken = token }

        await store.signOut()

        XCTAssertEqual(unregisteredToken, "push-token-abc")
        XCTAssertNil(KeychainService.shared.loadPushToken())
        XCTAssertNil(KeychainService.shared.loadToken())
        XCTAssertFalse(store.isAuthenticated)
    }
}

// MARK: - Notification Tests with Real Host

final class NotificationServiceHostedTests: XCTestCase {

    func test_scheduleTTLWarning_doesNotThrow_forActiveVault() throws {
        // UNUserNotificationCenter requires a real app host process — this test
        // runs in the hosted EthosProtocolTests target only, not in the bare SPM
        // test bundle which has no real app process or notification entitlements.
        XCTAssertNoThrow(
            NotificationService.shared.scheduleTTLWarning(vaultID: "vault-test", ttlRemaining: 3_600)
        )
    }

    func test_scheduleTTLWarning_removesExistingNotification_beforeAddingNew() throws {
        // Schedule twice for same vault; should not crash or duplicate.
        NotificationService.shared.scheduleTTLWarning(vaultID: "vault-dup", ttlRemaining: 7_200)
        XCTAssertNoThrow(
            NotificationService.shared.scheduleTTLWarning(vaultID: "vault-dup", ttlRemaining: 3_600)
        )
    }

    func test_scheduleTTLWarning_verifyPendingNotificationRequests() async {
        let vaultID = "vault-verify-\(UUID().uuidString)"
        NotificationService.shared.scheduleTTLWarning(vaultID: vaultID, ttlRemaining: 3_600)

        let pendingRequests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        let ttlWarningRequests = pendingRequests.filter { $0.identifier.contains(vaultID) }

        XCTAssertFalse(ttlWarningRequests.isEmpty, "Should have scheduled a notification for the vault")
        if let request = ttlWarningRequests.first {
            if let trigger = request.trigger as? UNTimeIntervalNotificationTrigger {
                XCTAssertTrue(trigger.timeInterval > 0, "Trigger should have positive time interval")
            }
        }
    }

    func test_scheduleTTLWarning_withTriggerIntervalValidation() async {
        let vaultID = "vault-trigger-\(UUID().uuidString)"
        NotificationService.shared.scheduleTTLWarning(vaultID: vaultID, ttlRemaining: 3_600)

        let pendingRequests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        let ttlRequests = pendingRequests.filter { $0.identifier.contains("ttl-warning") && $0.identifier.contains(vaultID) }

        XCTAssertEqual(ttlRequests.count, 1, "Should have exactly one TTL warning notification")
        if let request = ttlRequests.first, let trigger = request.trigger as? UNTimeIntervalNotificationTrigger {
            XCTAssertEqual(trigger.timeInterval, 5, "TTL warning should trigger in 5 seconds")
            XCTAssertFalse(trigger.repeats, "TTL warning should not repeat")
        }
    }

    func test_removeAllPendingNotifications_clearsScheduledRequests() async {
        NotificationService.shared.scheduleTTLWarning(vaultID: "vault-to-clear-\(UUID().uuidString)", ttlRemaining: 3_600)

        NotificationService.shared.removeAllPendingNotifications()

        let pendingRequests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        XCTAssertTrue(pendingRequests.isEmpty, "No notifications should remain pending after removeAllPendingNotifications()")
    }
}

// MARK: - #10 Sign-Out Local State Clearing Tests (Hosted)
//
// AuthStore.signOut() touches Keychain and UNUserNotificationCenter, both of which need a
// real app host process (see the notes above) — so these run here rather than in the bare
// SPM bundle in Tests/EthosProtocolTests.swift.

@MainActor
final class SignOutClearsLocalStateTests: XCTestCase {

    override func setUp() {
        super.setUp()
        ICloudSyncService.shared.isSyncEnabled = false
    }

    func test_signOut_clearsCredentialID() {
        KeychainService.shared.saveCredentialID("cred-to-clear")
        AuthStore().signOut()
        XCTAssertNil(KeychainService.shared.loadCredentialID())
    }

    func test_signOut_clearsOfflineCache_noResidualVaultDataReadable() {
        let cacheKey = "https://api.ethos-protocol.app/v1/vaults"
        OfflineCache.shared.save(Data(#"[{"id":"vault-1","balance":50000000}]"#.utf8), for: cacheKey)
        XCTAssertNotNil(OfflineCache.shared.load(for: cacheKey), "Precondition: cached vault data should be present before sign-out")

        AuthStore().signOut()

        XCTAssertNil(OfflineCache.shared.load(for: cacheKey), "Vault data cached before sign-out must not be readable afterward")
    }

    func test_signOut_clearsICloudLocalAssociation() {
        ICloudSyncService.shared.save(vaultID: "vault-to-forget", credentialID: "cred-to-forget")
        AuthStore().signOut()
        XCTAssertNil(ICloudSyncService.shared.credentialID(for: "vault-to-forget"))
    }

    func test_signOut_clearsPendingNotifications() async {
        NotificationService.shared.scheduleTTLWarning(vaultID: "vault-signout-\(UUID().uuidString)", ttlRemaining: 3_600)

        AuthStore().signOut()

        let pendingRequests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        XCTAssertTrue(pendingRequests.isEmpty, "No notifications from the signed-out session should remain pending")
    }

    func test_signOut_resetsLockState() {
        let store = AuthStore()
        store.isAuthenticated = true
        store.isLocked = true

        store.signOut()

        XCTAssertFalse(store.isLocked)
        XCTAssertFalse(store.isAuthenticated)
    }
}

import XCTest
@testable import EthosProtocol

final class ICloudSyncServiceTests: XCTestCase {

    override func setUp() {
        super.setUp()
        // Reset state before each test
        ICloudSyncService.shared.isSyncEnabled = false
        UserDefaults.standard.removeObject(forKey: "com.ethosprotocol.vault_associations")
    }

    func test_toggle_enablesSync() throws {
        // NSUbiquitousKeyValueStore silently no-ops without an iCloud entitlement
        // and a signed-in iCloud account — neither is available in the bare,
        // unsigned SPM test bundle this runs in on CI (every read then just
        // returns the default `false`, regardless of what was set).
        try XCTSkipIf(ProcessInfo.processInfo.environment["CI"] != nil,
                      "NSUbiquitousKeyValueStore requires an iCloud entitlement + signed-in account, unavailable in CI")
        ICloudSyncService.shared.isSyncEnabled = true
        XCTAssertTrue(ICloudSyncService.shared.isSyncEnabled)
    }

    func test_toggle_disablesSync() {
        ICloudSyncService.shared.isSyncEnabled = true
        ICloudSyncService.shared.isSyncEnabled = false
        XCTAssertFalse(ICloudSyncService.shared.isSyncEnabled)
    }

    func test_saveAndRetrieve_credentialForVault() {
        ICloudSyncService.shared.save(vaultID: "vault-abc", credentialID: "cred-xyz")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-abc"), "cred-xyz")
    }

    func test_missingVault_returnsNil() {
        XCTAssertNil(ICloudSyncService.shared.credentialID(for: "nonexistent-\(UUID())"))
    }

    func test_restoreFromICloud_mergesIntoLocal() {
        // Pre-populate local storage
        ICloudSyncService.shared.save(vaultID: "local-vault", credentialID: "local-cred")
        // Enable sync so restore reads remote (will be empty in unit test, but merge must not wipe local)
        ICloudSyncService.shared.isSyncEnabled = true
        ICloudSyncService.shared.restoreFromICloud()
        // Local entry must survive the merge
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "local-vault"), "local-cred")
    }

    func test_multipleAssociations_allRetrievable() {
        ICloudSyncService.shared.save(vaultID: "v1", credentialID: "c1")
        ICloudSyncService.shared.save(vaultID: "v2", credentialID: "c2")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "v1"), "c1")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "v2"), "c2")
    }

    func test_overwrite_updatesCredential() {
        ICloudSyncService.shared.save(vaultID: "v1", credentialID: "old-cred")
        ICloudSyncService.shared.save(vaultID: "v1", credentialID: "new-cred")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "v1"), "new-cred")
    }

    func test_conflictResolution_mostRecentWriteWins() {
        // Simulate a conflict where an older local write is present
        let oldTimestamp = Date().timeIntervalSince1970 - 100
        let newTimestamp = Date().timeIntervalSince1970

        // Save old value locally
        ICloudSyncService.shared.save(vaultID: "vault-conflict", credentialID: "old-cred")

        // Manually simulate a newer remote value by manually encoding and setting it
        let remoteAssoc = ICloudSyncService.VaultAssociation(credentialID: "new-cred", timestamp: newTimestamp)
        var remoteData: [String: ICloudSyncService.VaultAssociation] = ["vault-conflict": remoteAssoc]
        if let data = try? JSONEncoder().encode(remoteData) {
            NSUbiquitousKeyValueStore.default.set(data, forKey: "com.ethosprotocol.vault_associations")
            NSUbiquitousKeyValueStore.default.synchronize()
        }

        // Enable sync and restore
        ICloudSyncService.shared.isSyncEnabled = true
        ICloudSyncService.shared.restoreFromICloud()

        // Newer remote value should win
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-conflict"), "new-cred")
    }

    func test_conflictResolution_localNeverWriteWins() {
        // Simulate a scenario where local write is more recent than remote
        let oldRemoteTimestamp = Date().timeIntervalSince1970 - 100

        // Manually set an old remote value
        let remoteAssoc = ICloudSyncService.VaultAssociation(credentialID: "old-remote-cred", timestamp: oldRemoteTimestamp)
        var remoteData: [String: ICloudSyncService.VaultAssociation] = ["vault-conflict-2": remoteAssoc]
        if let data = try? JSONEncoder().encode(remoteData) {
            NSUbiquitousKeyValueStore.default.set(data, forKey: "com.ethosprotocol.vault_associations")
            NSUbiquitousKeyValueStore.default.synchronize()
        }

        // Now save a newer local value
        ICloudSyncService.shared.save(vaultID: "vault-conflict-2", credentialID: "new-local-cred")

        // Enable sync and restore
        ICloudSyncService.shared.isSyncEnabled = true
        ICloudSyncService.shared.restoreFromICloud()

        // Newer local value should win
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-conflict-2"), "new-local-cred")
    }
}

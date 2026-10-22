import XCTest
@testable import EthosProtocol

private final class InMemoryICloudKeyValueStore: ICloudKeyValueStore {
    private var values: [String: Any] = [:]

    func bool(forKey key: String) -> Bool { values[key] as? Bool ?? false }
    func data(forKey key: String) -> Data? { values[key] as? Data }
    func setBool(_ value: Bool, forKey key: String) { values[key] = value }
    func setData(_ value: Data, forKey key: String) { values[key] = value }
    func synchronize() -> Bool { true }
}

// MARK: - Manual QA Checklist for iCloud Sync
//
// Manual testing on a real device should verify:
// 1. Enable iCloud sync toggle on device (Settings > iCloud > Keychain)
// 2. Create a vault on Device A while sync is ON
// 3. Verify vault appears on Device B after sync completes
// 4. Create another vault on Device B
// 5. Verify both vaults appear on Device A after sync completes
// 6. Disable sync on Device A, create a vault
// 7. Verify new vault does NOT appear on Device B
// 8. Re-enable sync on Device A
// 9. Verify previously created vault now appears on Device B (one-way sync from A to cloud)

final class ICloudSyncServiceTests: XCTestCase {

    private var service: ICloudSyncService!
    private var remoteStore: InMemoryICloudKeyValueStore!
    private var userDefaults: UserDefaults!

    override func setUp() {
        super.setUp()
        let suiteName = "ICloudSyncServiceTests-\(UUID().uuidString)"
        userDefaults = UserDefaults(suiteName: suiteName)!
        userDefaults.removePersistentDomain(forName: suiteName)
        remoteStore = InMemoryICloudKeyValueStore()
        service = ICloudSyncService(remoteStore: remoteStore, userDefaults: userDefaults)
    }

    func test_toggle_enablesSync() {
        service.isSyncEnabled = true
        XCTAssertTrue(service.isSyncEnabled)
    }

    func test_toggle_disablesSync() {
        service.isSyncEnabled = true
        service.isSyncEnabled = false
        XCTAssertFalse(service.isSyncEnabled)
    }

    func test_saveAndRetrieve_credentialForVault() {
        service.save(vaultID: "vault-abc", credentialID: "cred-xyz")
        XCTAssertEqual(service.credentialID(for: "vault-abc"), "cred-xyz")
    }

    func test_missingVault_returnsNil() {
        XCTAssertNil(service.credentialID(for: "nonexistent-\(UUID())"))
    }

    // MARK: - #10 Clear Local State on Sign-Out

    func test_clearLocalAssociations_removesLocallySavedAssociation() {
        service.save(vaultID: "vault-to-clear", credentialID: "cred-to-clear")
        XCTAssertEqual(service.credentialID(for: "vault-to-clear"), "cred-to-clear")

        service.clearLocalAssociations()

        XCTAssertNil(service.credentialID(for: "vault-to-clear"))
    }

    func test_restoreFromICloud_mergesIntoLocal() {
        // Pre-populate local storage
        service.save(vaultID: "local-vault", credentialID: "local-cred")
        // Enable sync so restore reads remote (will be empty in unit test, but merge must not wipe local)
        service.isSyncEnabled = true
        service.restoreFromICloud()
        // Local entry must survive the merge
        XCTAssertEqual(service.credentialID(for: "local-vault"), "local-cred")
    }

    func test_restoreFromICloud_doesNothingWhenSyncDisabled() {
        // Pre-populate local storage
        service.save(vaultID: "vault-1", credentialID: "cred-1")
        // Don't enable sync
        service.isSyncEnabled = false
        service.restoreFromICloud()
        XCTAssertEqual(service.credentialID(for: "vault-1"), "cred-1")
    }

    func test_credentialID_prefers_remoteWhenSyncEnabled() throws {
        let remoteAssociation = ICloudSyncService.VaultAssociation(
            credentialID: "remote-version",
            timestamp: Date().timeIntervalSince1970 + 100
        )
        let remoteData = try JSONEncoder().encode(["shared-vault": remoteAssociation])
        remoteStore.setData(remoteData, forKey: "com.ethosprotocol.vault_associations")

        service.save(vaultID: "shared-vault", credentialID: "local-version")
        service.isSyncEnabled = true
        XCTAssertEqual(service.credentialID(for: "shared-vault"), "remote-version")
    }

    func test_associationMerge_multipleVaults() {
        // Test that multiple vault associations can be saved and retrieved
        service.save(vaultID: "vault-a", credentialID: "cred-a")
        service.save(vaultID: "vault-b", credentialID: "cred-b")
        service.save(vaultID: "vault-c", credentialID: "cred-c")

        XCTAssertEqual(service.credentialID(for: "vault-a"), "cred-a")
        XCTAssertEqual(service.credentialID(for: "vault-b"), "cred-b")
        XCTAssertEqual(service.credentialID(for: "vault-c"), "cred-c")
    }

    func test_multipleAssociations_allRetrievable() {
        service.save(vaultID: "v1", credentialID: "c1")
        service.save(vaultID: "v2", credentialID: "c2")
        XCTAssertEqual(service.credentialID(for: "v1"), "c1")
        XCTAssertEqual(service.credentialID(for: "v2"), "c2")
    }

    func test_overwrite_updatesCredential() {
        service.save(vaultID: "v1", credentialID: "old-cred")
        service.save(vaultID: "v1", credentialID: "new-cred")
        XCTAssertEqual(service.credentialID(for: "v1"), "new-cred")
    }

    func test_conflictResolution_mostRecentWriteWins() throws {
        // Simulate a conflict where an older local write is present
        let newTimestamp = Date().timeIntervalSince1970 + 100

        // Save old value locally
        service.save(vaultID: "vault-conflict", credentialID: "old-cred")

        // Manually simulate a newer remote value by manually encoding and setting it
        let remoteAssoc = ICloudSyncService.VaultAssociation(credentialID: "new-cred", timestamp: newTimestamp)
        var remoteData: [String: ICloudSyncService.VaultAssociation] = ["vault-conflict": remoteAssoc]
        if let data = try? JSONEncoder().encode(remoteData) {
            remoteStore.setData(data, forKey: "com.ethosprotocol.vault_associations")
            remoteStore.synchronize()
        }

        // Enable sync and restore
        service.isSyncEnabled = true
        service.restoreFromICloud()

        // Newer remote value should win
        XCTAssertEqual(service.credentialID(for: "vault-conflict"), "new-cred")
    }

    func test_conflictResolution_localNeverWriteWins() {
        // Simulate a scenario where local write is more recent than remote
        let oldRemoteTimestamp = Date().timeIntervalSince1970 - 100

        // Manually set an old remote value
        let remoteAssoc = ICloudSyncService.VaultAssociation(credentialID: "old-remote-cred", timestamp: oldRemoteTimestamp)
        var remoteData: [String: ICloudSyncService.VaultAssociation] = ["vault-conflict-2": remoteAssoc]
        if let data = try? JSONEncoder().encode(remoteData) {
            remoteStore.setData(data, forKey: "com.ethosprotocol.vault_associations")
            remoteStore.synchronize()
        }

        // Now save a newer local value
        service.save(vaultID: "vault-conflict-2", credentialID: "new-local-cred")

        // Enable sync and restore
        service.isSyncEnabled = true
        service.restoreFromICloud()

        // Newer local value should win
        XCTAssertEqual(service.credentialID(for: "vault-conflict-2"), "new-local-cred")
    }

    // MARK: - #205 Simultaneous Multi-Device Check-In Conflict Resolution
    //
    // Exercises `ICloudSyncService.merge(local:remote:)` directly, rather than through
    // `NSUbiquitousKeyValueStore`, so these run reliably in CI (no iCloud entitlement needed).

    func test_merge_simultaneousDevices_disjointVaults_keepsBoth() {
        // Device A queued an offline check-in/association for vault-1 while Device B,
        // also offline, queued one for vault-2. Neither should be dropped once both sync.
        let deviceA: [String: ICloudSyncService.VaultAssociation] = [
            "vault-1": .init(credentialID: "cred-a", timestamp: 100)
        ]
        let deviceB: [String: ICloudSyncService.VaultAssociation] = [
            "vault-2": .init(credentialID: "cred-b", timestamp: 101)
        ]
        let merged = ICloudSyncService.merge(local: deviceA, remote: deviceB)
        XCTAssertEqual(merged["vault-1"]?.credentialID, "cred-a")
        XCTAssertEqual(merged["vault-2"]?.credentialID, "cred-b")
    }

    func test_merge_simultaneousDevices_sameVault_newerTimestampWins() {
        // Both devices raced to check in on the same vault; the server-accepted-order
        // proxy (persist timestamp) must decide deterministically, not "whichever device
        // pushed to iCloud last".
        let older: [String: ICloudSyncService.VaultAssociation] = [
            "vault-shared": .init(credentialID: "device-a-cred", timestamp: 200)
        ]
        let newer: [String: ICloudSyncService.VaultAssociation] = [
            "vault-shared": .init(credentialID: "device-b-cred", timestamp: 205)
        ]
        XCTAssertEqual(ICloudSyncService.merge(local: older, remote: newer)["vault-shared"]?.credentialID, "device-b-cred")
        // Order-independent: the newer entry wins regardless of which side is "local" vs "remote".
        XCTAssertEqual(ICloudSyncService.merge(local: newer, remote: older)["vault-shared"]?.credentialID, "device-b-cred")
    }

    func test_pushAssociations_doesNotClobberNewerRemoteEntryForUntouchedVault() {
        // Simulates the double check-in race end-to-end: Device A pushes its local state
        // (which knows nothing about vault-2) after Device B already pushed a newer entry
        // for vault-2. Device A's push must not delete vault-2's association from iCloud.
        let deviceBAssoc = ICloudSyncService.VaultAssociation(credentialID: "device-b-cred", timestamp: Date().timeIntervalSince1970)
        if let data = try? JSONEncoder().encode(["vault-2": deviceBAssoc]) {
            remoteStore.setData(data, forKey: "com.ethosprotocol.vault_associations")
            remoteStore.synchronize()
        }

        service.isSyncEnabled = true
        service.save(vaultID: "vault-1", credentialID: "device-a-cred")

        XCTAssertEqual(service.credentialID(for: "vault-1"), "device-a-cred")
        XCTAssertEqual(service.credentialID(for: "vault-2"), "device-b-cred")
    }
}

import Foundation

protocol ICloudKeyValueStore {
    func bool(forKey key: String) -> Bool
    func data(forKey key: String) -> Data?
    func setBool(_ value: Bool, forKey key: String)
    func setData(_ value: Data, forKey key: String)
    func synchronize() -> Bool
}

extension NSUbiquitousKeyValueStore: ICloudKeyValueStore {
    func setBool(_ value: Bool, forKey key: String) { set(value, forKey: key) }
    func setData(_ value: Data, forKey key: String) { set(value, forKey: key) }
}

/// Manages optional iCloud sync for passkey-to-vault association metadata.
/// Only non-sensitive data (vault IDs, credential IDs) is synced — never private keys.
final class ICloudSyncService {
    static let shared = ICloudSyncService()

    private let store: ICloudKeyValueStore
    private let userDefaults: UserDefaults
    private let enabledKey = "com.ethosprotocol.icloud_sync_enabled"
    private let associationsKey = "com.ethosprotocol.vault_associations"

    init(remoteStore: ICloudKeyValueStore = NSUbiquitousKeyValueStore.default,
         userDefaults: UserDefaults = .standard) {
        store = remoteStore
        self.userDefaults = userDefaults
    }

    // MARK: - Association Model

    struct VaultAssociation: Codable, Equatable {
        let credentialID: String
        let timestamp: TimeInterval
    }

    // MARK: - Toggle

    var isSyncEnabled: Bool {
        get { store.bool(forKey: enabledKey) }
        set {
            store.setBool(newValue, forKey: enabledKey)
            store.synchronize()
            if newValue { pushAssociations(loadLocalAssociations()) }
        }
    }

    // MARK: - Associations

    /// Save a vault-to-credential association locally; push to iCloud if sync is on.
    ///
    /// Conflict rule (#205): last-write-wins per vault ID, compared by `timestamp` — the
    /// moment this device durably persisted the association, immediately following the
    /// server-accepted passkey registration or check-in it records. `pushAssociations`
    /// merges against the current remote state rather than overwriting it outright, so two
    /// devices racing to sync after each queued an offline check-in for the same vault
    /// converge on the newer association instead of one device's push silently discarding
    /// the other's.
    func save(vaultID: String, credentialID: String) {
        var assoc = loadLocalAssociations()
        assoc[vaultID] = VaultAssociation(credentialID: credentialID, timestamp: Date().timeIntervalSince1970)
        persist(assoc)
        if isSyncEnabled { pushAssociations(assoc) }
    }

    /// Return the credential ID associated with a vault, checking iCloud first when sync is on.
    func credentialID(for vaultID: String) -> String? {
        if isSyncEnabled, let remoteAssociation = remoteAssociations()[vaultID] { return remoteAssociation.credentialID }
        return loadLocalAssociations()[vaultID]?.credentialID
    }

    /// Clears this device's local vault-to-credential associations (used on sign-out).
    /// Deliberately leaves the iCloud key-value store itself untouched — wiping that would
    /// also erase the association for any other device still signed in; restoreFromICloud()
    /// repopulates local storage from iCloud on the next sign-in if sync is re-enabled.
    func clearLocalAssociations() {
        userDefaults.removeObject(forKey: associationsKey)
    }

    /// Pull associations from iCloud and merge into local storage using last-write-wins strategy.
    func restoreFromICloud() {
        guard isSyncEnabled else { return }
        persist(Self.merge(local: loadLocalAssociations(), remote: remoteAssociations()))
    }

    /// Merges two association maps using the last-write-wins conflict rule (#205): for a
    /// vault ID present on both sides, the entry with the newer `timestamp` wins; a vault ID
    /// present on only one side is kept as-is. `internal` (not `private`) so
    /// ICloudSyncServiceTests can exercise the merge/conflict rule directly, without needing
    /// the iCloud entitlement NSUbiquitousKeyValueStore requires (unavailable in CI).
    static func merge(local: [String: VaultAssociation], remote: [String: VaultAssociation]) -> [String: VaultAssociation] {
        var merged = local
        for (vaultID, remoteValue) in remote {
            if let localValue = merged[vaultID] {
                if remoteValue.timestamp > localValue.timestamp { merged[vaultID] = remoteValue }
            } else {
                merged[vaultID] = remoteValue
            }
        }
        return merged
    }

    // MARK: - Private helpers

    private func loadLocalAssociations() -> [String: VaultAssociation] {
        guard let data = userDefaults.data(forKey: associationsKey),
              let dict = try? JSONDecoder().decode([String: VaultAssociation].self, from: data) else { return [:] }
        return dict
    }

    private func persist(_ associations: [String: VaultAssociation]) {
        guard let data = try? JSONEncoder().encode(associations) else { return }
        userDefaults.set(data, forKey: associationsKey)
    }

    private func pushAssociations(_ associations: [String: VaultAssociation]) {
        // #205: merge against the current remote state instead of overwriting it wholesale —
        // otherwise a second device's push, racing this one, could clobber an association the
        // first device just wrote for a vault the second device never touched locally.
        let merged = Self.merge(local: associations, remote: remoteAssociations())
        guard let data = try? JSONEncoder().encode(merged) else { return }
        store.setData(data, forKey: associationsKey)
        store.synchronize()
    }

    private func remoteAssociations() -> [String: VaultAssociation] {
        guard let data = store.data(forKey: associationsKey),
              let dict = try? JSONDecoder().decode([String: VaultAssociation].self, from: data) else { return [:] }
        return dict
    }
}

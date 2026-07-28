import Foundation

/// Manages optional iCloud sync for passkey-to-vault association metadata.
/// Only non-sensitive data (vault IDs, credential IDs) is synced — never private keys.
final class ICloudSyncService {
    static let shared = ICloudSyncService()
    private init() {}

    private let store = NSUbiquitousKeyValueStore.default
    private let enabledKey = "com.ethosprotocol.icloud_sync_enabled"
    private let associationsKey = "com.ethosprotocol.vault_associations"

    // MARK: - Association Model

    struct VaultAssociation: Codable, Equatable {
        let credentialID: String
        let timestamp: TimeInterval
    }

    // MARK: - Toggle

    var isSyncEnabled: Bool {
        get { store.bool(forKey: enabledKey) }
        set {
            store.set(newValue, forKey: enabledKey)
            store.synchronize()
            if newValue { pushAssociations(loadLocalAssociations()) }
        }
    }

    // MARK: - Associations

    /// Save a vault-to-credential association locally; push to iCloud if sync is on.
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
        UserDefaults.standard.removeObject(forKey: associationsKey)
    }

    /// Pull associations from iCloud and merge into local storage using last-write-wins strategy.
    func restoreFromICloud() {
        guard isSyncEnabled else { return }
        let remote = remoteAssociations()
        var local = loadLocalAssociations()
        for (k, remoteValue) in remote {
            if let localValue = local[k] {
                local[k] = remoteValue.timestamp > localValue.timestamp ? remoteValue : localValue
            } else {
                local[k] = remoteValue
            }
        }
        persist(local)
    }

    // MARK: - Private helpers

    private func loadLocalAssociations() -> [String: VaultAssociation] {
        guard let data = UserDefaults.standard.data(forKey: associationsKey),
              let dict = try? JSONDecoder().decode([String: VaultAssociation].self, from: data) else { return [:] }
        return dict
    }

    private func persist(_ associations: [String: VaultAssociation]) {
        guard let data = try? JSONEncoder().encode(associations) else { return }
        UserDefaults.standard.set(data, forKey: associationsKey)
    }

    private func pushAssociations(_ associations: [String: VaultAssociation]) {
        guard let data = try? JSONEncoder().encode(associations) else { return }
        store.set(data, forKey: associationsKey)
        store.synchronize()
    }

    private func remoteAssociations() -> [String: VaultAssociation] {
        guard let data = store.data(forKey: associationsKey),
              let dict = try? JSONDecoder().decode([String: VaultAssociation].self, from: data) else { return [:] }
        return dict
    }
}

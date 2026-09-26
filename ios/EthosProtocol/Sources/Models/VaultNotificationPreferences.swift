import Foundation

/// Per-vault notification preferences for expiry warnings.
/// Allows users to customize when they receive notifications before vault expiry.
struct VaultNotificationPreferences: Codable, Equatable {
    /// The vault ID this preference applies to.
    let vaultID: String

    /// Notification interval options in days before expiry.
    /// Default is [1] (notify 1 day before).
    var notificationDays: [Int]

    init(vaultID: String, notificationDays: [Int] = [1]) {
        self.vaultID = vaultID
        self.notificationDays = notificationDays.sorted().reversed()
    }

    private static let userDefaultsPrefix = "com.ethosprotocol.vault_notification_prefs."

    /// Load preferences for a specific vault, or return defaults.
    static func load(for vaultID: String) -> VaultNotificationPreferences {
        let key = userDefaultsPrefix + vaultID
        guard let data = UserDefaults.standard.data(forKey: key),
              let prefs = try? JSONDecoder().decode(VaultNotificationPreferences.self, from: data)
        else {
            return VaultNotificationPreferences(vaultID: vaultID, notificationDays: [1])
        }
        return prefs
    }

    /// Save preferences for this vault.
    func save() {
        let key = VaultNotificationPreferences.userDefaultsPrefix + vaultID
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    /// Delete preferences for a vault (e.g., when vault is removed).
    static func delete(for vaultID: String) {
        let key = userDefaultsPrefix + vaultID
        UserDefaults.standard.removeObject(forKey: key)
    }

    /// Get all scheduled notification times for this vault in seconds.
    /// Returns the number of seconds before expiry for each configured interval.
    func getNotificationSecondsBefore(ttlRemaining: UInt64) -> [UInt64] {
        notificationDays.compactMap { days -> UInt64? in
            let seconds = UInt64(days * 86_400)
            // Only include notifications that are still in the future
            return seconds <= ttlRemaining ? seconds : nil
        }
    }
}

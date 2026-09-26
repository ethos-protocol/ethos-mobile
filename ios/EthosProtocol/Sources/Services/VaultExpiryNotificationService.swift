import UserNotifications
import Foundation

/// Manages vault expiry notifications with customizable intervals per vault.
/// Replaces the simpler 24-hour fixed notification with per-vault configurable intervals.
final class VaultExpiryNotificationService {
    static let shared = VaultExpiryNotificationService()

    private init() {}

    /// Schedules vault expiry notifications based on the vault's custom preferences.
    /// If custom preferences don't exist, defaults to notifying 1 day before expiry.
    func scheduleVaultExpiryNotifications(vaultID: String, vaultName: String, ttlRemaining: UInt64) {
        let center = UNUserNotificationCenter.current()

        // Remove any previously scheduled expiry notifications for this vault
        center.removePendingNotificationRequests(withIdentifiers: vaultExpiryIdentifiers(for: vaultID))

        guard ttlRemaining > 0 else { return }

        let prefs = VaultNotificationPreferences.load(for: vaultID)

        for (index, days) in prefs.notificationDays.enumerated() {
            let secondsBefore = UInt64(days * 86_400)
            guard secondsBefore <= ttlRemaining else { continue }

            let fireInSeconds = Int(ttlRemaining) - Int(secondsBefore)
            guard fireInSeconds > 0 else { continue }

            let content = UNMutableNotificationContent()

            if days == 1 {
                content.title = "Vault Expiring Tomorrow"
            } else if days == 3 {
                content.title = "Vault Expires in 3 Days"
            } else if days == 7 {
                content.title = "Vault Expires in 1 Week"
            } else {
                content.title = "Vault Expiring Soon"
            }

            let remainingAtFire = max(Int(ttlRemaining) - fireInSeconds, 0)
            let formattedRemaining = formatTTLRemaining(remainingAtFire)
            content.body = "Vault \(truncatedVaultID(vaultID)) expires in \(formattedRemaining). Tap to check in and extend it."
            content.sound = .default
            content.userInfo = ["vault_id": vaultID]
            content.categoryIdentifier = "CHECK_IN"

            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(fireInSeconds), repeats: false)
            let requestID = "vault-expiry-\(vaultID)-\(index)"
            let request = UNNotificationRequest(identifier: requestID, content: content, trigger: trigger)

            center.add(request)
            NotificationDeliveryLog.shared.record(kind: .scheduled, source: .local, eventType: "vault_expiry_\(days)d", vaultID: vaultID)
        }
    }

    /// Removes all scheduled notifications for a vault.
    func removeVaultExpiryNotifications(for vaultID: String) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: vaultExpiryIdentifiers(for: vaultID))
    }

    /// Updates notifications when vault preferences change.
    /// Call this after the user changes notification preferences for a vault.
    func updateNotificationsIfNeeded(vaultID: String, currentVault: Vault?) {
        if let vault = currentVault {
            scheduleVaultExpiryNotifications(vaultID: vaultID, vaultName: truncatedVaultID(vaultID), ttlRemaining: vault.ttlRemaining ?? 0)
        }
    }

    // MARK: - Private Helpers

    private func vaultExpiryIdentifiers(for vaultID: String) -> [String] {
        let prefs = VaultNotificationPreferences.load(for: vaultID)
        return prefs.notificationDays.indices.map { "vault-expiry-\(vaultID)-\($0)" }
    }

    private func truncatedVaultID(_ vaultID: String) -> String {
        String(vaultID.prefix(12))
    }

    private func formatTTLRemaining(_ seconds: Int) -> String {
        let clamped = max(seconds, 0)
        let days = clamped / 86_400
        let hours = (clamped % 86_400) / 3_600
        if days > 0 { return "\(days)d \(hours)h" }
        let minutes = (clamped % 3_600) / 60
        if hours > 0 { return "\(hours)h \(minutes)m" }
        return "\(minutes)m"
    }
}

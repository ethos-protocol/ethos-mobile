import Foundation
import os.log

final class SilentNotificationService {
    static let shared = SilentNotificationService()

    private static let log = OSLog(subsystem: "app.ethos-protocol", category: "silent-notifications")
    private static let lastSilentNotificationKey = "com.ethosprotocol.last_silent_notification"
    private static let minimumFetchInterval: TimeInterval = 15 * 60 // 15 minutes

    // Injectable for testing
    var vaultListProvider: () async throws -> [Vault] = { try await APIClient.shared.listAllVaults() }

    private override init() {}

    /// Handles a silent notification (content-available) by fetching vault data if enough time
    /// has passed since the last fetch. Returns true if data was fetched, false if throttled.
    func handleSilentNotification() -> (shouldFetch: Bool, backgroundTaskRemainingTime: TimeInterval) {
        let now = Date()
        let lastFetch = UserDefaults.standard.object(forKey: Self.lastSilentNotificationKey) as? Date ?? Date(timeIntervalSince1970: 0)
        let timeSinceLastFetch = now.timeIntervalSince(lastFetch)

        let shouldFetch = timeSinceLastFetch >= Self.minimumFetchInterval
        Self.log.log("silent notification received: shouldFetch=\(shouldFetch), timeSinceLast=\(Int(timeSinceLastFetch))s")

        if shouldFetch {
            UserDefaults.standard.set(now, forKey: Self.lastSilentNotificationKey)
        }

        return (shouldFetch: shouldFetch, backgroundTaskRemainingTime: 30)
    }

    /// Fetches vault data in response to a silent notification.
    /// This should be called from the app delegate's didReceiveRemoteNotification handler.
    func fetchVaultDataInBackground(completion: @escaping (UIBackgroundFetchResult) -> Void) {
        Task {
            do {
                let (shouldFetch, _) = handleSilentNotification()
                guard shouldFetch else {
                    Self.log.log("silent notification fetch throttled")
                    completion(.noData)
                    return
                }

                let vaults = try await vaultListProvider()
                Self.log.log("silent notification fetch completed: \(vaults.count) vaults")

                // Check for any vaults with TTL < 24 hours and schedule warnings
                for vault in vaults where vault.status == .active {
                    if let ttl = vault.ttlRemaining, ttl < 86_400 {
                        NotificationService.shared.scheduleTTLWarning(vaultID: vault.id, ttlRemaining: ttl)
                    }
                }

                completion(.newData)
            } catch {
                Self.log.log("silent notification fetch failed: \(error.localizedDescription)")
                completion(.failed)
            }
        }
    }

    /// Resets the last fetch timestamp for testing purposes.
    func resetLastFetchTime() {
        UserDefaults.standard.removeObject(forKey: Self.lastSilentNotificationKey)
    }
}

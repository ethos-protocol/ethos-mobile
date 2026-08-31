import Foundation

/// Per-category notification preferences and optional quiet hours.
/// Persisted to UserDefaults and registered with the push-token endpoint on change
/// so preferences survive reinstall. Mirrors Android NotificationPreferences.
struct NotificationPreferences: Codable, Equatable {
    /// Whether TTL-expiry warning notifications are enabled.
    var ttlWarningsEnabled: Bool
    /// Whether check-in reminder notifications are enabled.
    var checkInRemindersEnabled: Bool
    /// Whether quiet hours are active (notifications suppressed in the quiet window).
    var quietHoursEnabled: Bool
    /// Start of quiet hours (hour component, 0–23, local time).
    var quietHoursStart: Int
    /// End of quiet hours (hour component, 0–23, local time).
    var quietHoursEnd: Int

    static let `default` = NotificationPreferences(
        ttlWarningsEnabled: true,
        checkInRemindersEnabled: true,
        quietHoursEnabled: false,
        quietHoursStart: 22,
        quietHoursEnd: 8
    )

    private static let userDefaultsKey = "com.ethosprotocol.notification_preferences"

    static var current: NotificationPreferences {
        get {
            guard let data = UserDefaults.standard.data(forKey: userDefaultsKey),
                  let prefs = try? JSONDecoder().decode(NotificationPreferences.self, from: data)
            else { return .default }
            return prefs
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                UserDefaults.standard.set(data, forKey: userDefaultsKey)
            }
        }
    }

    /// Returns true if a notification should be suppressed right now based on quiet hours.
    func isSuppressedByQuietHours(at date: Date = Date(), calendar: Calendar = .current) -> Bool {
        guard quietHoursEnabled else { return false }
        let hour = calendar.component(.hour, from: date)
        if quietHoursStart <= quietHoursEnd {
            // e.g. 09:00–17:00
            return hour >= quietHoursStart && hour < quietHoursEnd
        } else {
            // Wraps midnight, e.g. 22:00–08:00
            return hour >= quietHoursStart || hour < quietHoursEnd
        }
    }
}

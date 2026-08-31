import Foundation

/// Local, on-device record of notification lifecycle events (scheduled /
/// delivered / suppressed), so a support ticket like "I never got my TTL
/// warning" is answerable from a debug screen instead of requiring backend
/// log correlation (#235).
///
/// Doubles as the dedup registry for #232: a `vault_expired`/`vault_released`
/// event applied via the WebSocket is recorded here, and the push-delivery
/// path (`NotificationService`'s `willPresent`) checks it before showing a
/// banner for the same event.
///
/// **No sensitive vault data is ever recorded** — only a vault ID, an event
/// type string (e.g. `"vault_expired"`, `"ttl_warning"`), a delivery
/// channel, and a timestamp. Never balance, beneficiary, or any other vault
/// field.
struct NotificationDeliveryEvent: Codable, Equatable, Identifiable {
    enum Kind: String, Codable { case scheduled, delivered, suppressed }
    enum Source: String, Codable { case local, push, websocket }

    var id = UUID()
    let kind: Kind
    let source: Source
    /// e.g. "vault_expired", "vault_released", "check_in_reminder", "ttl_warning".
    /// Never raw vault content.
    let eventType: String
    /// Vault ID only.
    let vaultID: String
    let timestamp: Date
}

final class NotificationDeliveryLog {
    static let shared = NotificationDeliveryLog()

    /// Bounded so this never grows unbounded across a long-lived install —
    /// only recent history is useful for support triage.
    private let maxEntries = 200
    private let userDefaultsKey = "com.ethosprotocol.notification_delivery_log"
    private let defaults: UserDefaults
    private let queue = DispatchQueue(label: "com.ethosprotocol.notification-delivery-log")

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func record(kind: NotificationDeliveryEvent.Kind, source: NotificationDeliveryEvent.Source,
                eventType: String, vaultID: String, at timestamp: Date = Date()) {
        let event = NotificationDeliveryEvent(kind: kind, source: source, eventType: eventType,
                                               vaultID: vaultID, timestamp: timestamp)
        queue.sync {
            var all = loadLocked()
            all.append(event)
            if all.count > maxEntries {
                all.removeFirst(all.count - maxEntries)
            }
            saveLocked(all)
        }
    }

    /// All logged events, most recent first, for the debug screen.
    func recentEvents() -> [NotificationDeliveryEvent] {
        queue.sync { loadLocked().reversed() }
    }

    func clear() {
        queue.sync { defaults.removeObject(forKey: userDefaultsKey) }
    }

    /// #232: was `eventType` for `vaultID` already delivered via the WebSocket
    /// within `window` seconds of `now`? Used to suppress a duplicate push
    /// banner for an event the UI already reflects.
    func wasRecentlyDeliveredViaWebSocket(vaultID: String, eventType: String,
                                          within window: TimeInterval = 30, now: Date = Date()) -> Bool {
        queue.sync {
            loadLocked().contains { event in
                event.source == .websocket
                    && event.kind == .delivered
                    && event.vaultID == vaultID
                    && event.eventType == eventType
                    && now.timeIntervalSince(event.timestamp) <= window
                    && now.timeIntervalSince(event.timestamp) >= 0
            }
        }
    }

    private func loadLocked() -> [NotificationDeliveryEvent] {
        guard let data = defaults.data(forKey: userDefaultsKey),
              let decoded = try? JSONDecoder().decode([NotificationDeliveryEvent].self, from: data) else {
            return []
        }
        return decoded
    }

    private func saveLocked(_ events: [NotificationDeliveryEvent]) {
        guard let data = try? JSONEncoder().encode(events) else { return }
        defaults.set(data, forKey: userDefaultsKey)
    }
}

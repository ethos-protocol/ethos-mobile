import Foundation

final class DuplicateVaultLogger {
    static let shared = DuplicateVaultLogger()
    private init() {}

    private let userDefaultsKey = "com.ethosprotocol.duplicate_vault_events"
    private let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        formatter.timeZone = TimeZone(abbreviation: "UTC")
        return formatter
    }()

    struct DuplicateVaultEvent: Codable {
        let timestamp: String
        let count: Int
    }

    func logDeduplication(count: Int) {
        let event = DuplicateVaultEvent(
            timestamp: dateFormatter.string(from: Date()),
            count: count
        )

        var events = loadEvents()
        events.append(event)
        saveEvents(events)
    }

    func getEvents() -> [DuplicateVaultEvent] {
        loadEvents()
    }

    func clearEvents() {
        UserDefaults.standard.removeObject(forKey: userDefaultsKey)
    }

    private func loadEvents() -> [DuplicateVaultEvent] {
        guard let data = UserDefaults.standard.data(forKey: userDefaultsKey),
              let events = try? JSONDecoder().decode([DuplicateVaultEvent].self, from: data) else {
            return []
        }
        return events
    }

    private func saveEvents(_ events: [DuplicateVaultEvent]) {
        if let data = try? JSONEncoder().encode(events) {
            UserDefaults.standard.set(data, forKey: userDefaultsKey)
        }
    }
}

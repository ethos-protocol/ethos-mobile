import Foundation

// MARK: - PendingCheckIn

/// A single queued check-in that could not be delivered while the device was offline.
/// Mirrors the CHECK_IN case of Android's `PendingAction` Room entity (PendingAction.kt).
struct PendingCheckIn: Codable, Equatable {
    let vaultId: String
    let queuedAt: Date
}

// MARK: - PendingCheckInStore

/// Durable, disk-backed queue for offline check-ins.
///
/// Mirrors the CHECK_IN path of Android's `PendingActionDao` (PendingActionDatabase.kt) —
/// insert on offline check-in, delete on successful delivery, read by `CheckInSyncTask`
/// when connectivity returns.
///
/// Storage: a JSON file in the app's Application Support directory so it survives
/// app restarts (unlike UserDefaults, which can be evicted under storage pressure).
/// All mutations are synchronised on a serial queue to avoid data races.
final class PendingCheckInStore {
    static let shared = PendingCheckInStore()
    static let maxQueueSize = 50

    private let fileURL: URL
    private let queue = DispatchQueue(label: "com.ethosprotocol.PendingCheckInStore")

    // Injected for unit tests so we don't touch the real filesystem.
    init(fileURL: URL? = nil) {
        if let url = fileURL {
            self.fileURL = url
        } else {
            let support = FileManager.default
                .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("EthosProtocol", isDirectory: true)
            try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
            self.fileURL = support.appendingPathComponent("pending_checkins.json")
        }
    }

    // MARK: - Public API (matches PendingCheckInDao)

    /// Returns all queued check-ins, oldest first.
    func getAll() -> [PendingCheckIn] {
        queue.sync { load() }
    }

    /// Returns the current queue count (used by the notification badge).
    var count: Int { getAll().count }

    /// Returns true when the queue has reached its maximum capacity.
    var isAtCapacity: Bool { count >= Self.maxQueueSize }

    /// Returns true when the queue is within 5 items of its maximum capacity.
    var isNearCapacity: Bool { count >= Self.maxQueueSize - 5 }

    /// Enqueue a check-in for `vaultId`. Idempotent — a vault already in the queue
    /// is not duplicated (mirrors Android's `OnConflictStrategy.REPLACE`).
    func insert(_ item: PendingCheckIn) {
        queue.sync {
            var items = load()
            items.removeAll { $0.vaultId == item.vaultId }
            items.append(item)
            if items.count > Self.maxQueueSize { items.removeFirst(items.count - Self.maxQueueSize) }
            save(items)
        }
    }

    /// Remove a successfully-delivered check-in from the queue.
    func delete(_ item: PendingCheckIn) {
        queue.sync {
            var items = load()
            items.removeAll { $0.vaultId == item.vaultId }
            save(items)
        }
    }

    /// Wipe the entire queue (used in tests and on sign-out).
    func deleteAll() {
        queue.sync { save([]) }
    }

    // MARK: - Private helpers

    private func load() -> [PendingCheckIn] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode([PendingCheckIn].self, from: data)) ?? []
    }

    private func save(_ items: [PendingCheckIn]) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = .prettyPrinted
        guard let data = try? encoder.encode(items) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}

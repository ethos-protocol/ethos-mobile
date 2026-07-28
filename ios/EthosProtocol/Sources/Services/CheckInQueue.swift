import Foundation

/// A check-in attempted while offline, waiting to be retried once connectivity returns.
struct PendingCheckIn: Codable, Equatable {
    let vaultID: String
    let queuedAt: Date
}

/// Durable, file-backed queue for check-ins attempted while offline, so a missed connectivity
/// window doesn't silently drop a dead-man's-switch check-in. Mirrors Android's
/// PendingCheckInDao (android/.../services/CheckInQueue.kt) using a flat JSON file instead of
/// Room, since this app has no other on-device database to extend.
final class CheckInQueue {
    static let shared = CheckInQueue()

    private let fileURL: URL
    private let queue = DispatchQueue(label: "com.ethosprotocol.checkinqueue")

    // `internal` (not `private`): lets tests construct an isolated instance pointed at a
    // scratch directory via `@testable import`, instead of mutating the real `.shared` queue.
    init(directory: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("pending_checkins.json")
    }

    /// Vaults with a check-in currently queued for retry, oldest first.
    var pending: [PendingCheckIn] {
        queue.sync { readAll() }
    }

    var count: Int { pending.count }

    /// Queues `vaultID` for retry, replacing any existing queued entry for the same vault
    /// rather than duplicating it.
    func enqueue(vaultID: String) {
        queue.sync {
            var all = readAll().filter { $0.vaultID != vaultID }
            all.append(PendingCheckIn(vaultID: vaultID, queuedAt: Date()))
            write(all)
        }
    }

    func remove(vaultID: String) {
        queue.sync {
            write(readAll().filter { $0.vaultID != vaultID })
        }
    }

    func removeAll() {
        queue.sync { write([]) }
    }

    private func readAll() -> [PendingCheckIn] {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode([PendingCheckIn].self, from: data) else { return [] }
        return decoded
    }

    private func write(_ items: [PendingCheckIn]) {
        guard let data = try? JSONEncoder().encode(items) else { return }
        try? data.write(to: fileURL)
    }
}

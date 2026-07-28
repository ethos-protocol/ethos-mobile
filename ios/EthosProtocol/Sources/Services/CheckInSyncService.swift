import Foundation

/// Retries every queued check-in. Mirrors Android's CheckInSyncWorker
/// (android/.../services/CheckInSyncWorker.kt): a check-in is a dead-man's-switch signal, so
/// only errors the server has definitively rejected as invalid (the vault no longer exists)
/// drop the queued item — everything else (offline, timeout, server error, expired auth) is
/// left queued for the next retry rather than risking a vault being released even though the
/// user did check in.
final class CheckInSyncService {
    static let shared = CheckInSyncService()

    var queue: CheckInQueue = .shared

    // Injected for testing; defaults to a real check-in call.
    var checkInProvider: (String) async throws -> Void = { try await APIClient.shared.checkIn(vaultID: $0) }

    // `internal` (not `private`): lets tests construct an isolated instance with its own
    // queue/provider via `@testable import`, instead of mutating the real `.shared` service.
    init() {}

    /// Attempts every queued check-in, dropping only the non-retryable failures, and leaves
    /// the "check-in queued" notification up to date with what's left afterward.
    @discardableResult
    func flush() async -> Bool {
        let items = queue.pending
        // Nothing queued means nothing to do — in particular, skip touching
        // NotificationService entirely so a routine flush with an empty queue (e.g. every
        // hourly BGAppRefreshTask tick) doesn't needlessly re-post/cancel a notification.
        guard !items.isEmpty else { return true }

        var allSucceeded = true
        for item in items {
            do {
                try await checkInProvider(item.vaultID)
                queue.remove(vaultID: item.vaultID)
            } catch APIError.notFound {
                // The server has told us unambiguously this check-in can never succeed
                // (the vault no longer exists) — retrying it is pointless.
                queue.remove(vaultID: item.vaultID)
            } catch {
                allSucceeded = false
            }
        }

        let remaining = queue.count
        if remaining > 0 {
            NotificationService.shared.showQueuedCheckIn(count: remaining)
        } else {
            NotificationService.shared.cancelQueuedCheckIn()
        }
        return allSucceeded
    }
}

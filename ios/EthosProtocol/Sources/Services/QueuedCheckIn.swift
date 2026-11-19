import Foundation

/// Single entry point for check-ins triggered outside the app's main UI (widget, App Shortcuts).
///
/// #790: these paths previously called `APIClient.checkIn(vaultID:idempotencyKey: nil)` directly,
/// so a timed-out-then-repeated tap could record two check-ins and an offline tap was lost.
/// This routes them through the same idempotency key + `PendingCheckInStore` queue that the
/// in-app check-in path uses.
enum QueuedCheckIn {

    typealias Send = (_ vaultID: String, _ idempotencyKey: String) async throws -> Void

    /// Sends a check-in with a stable idempotency key. If the device is offline the check-in is
    /// persisted to `store` under that same key so the sync task can deliver it later.
    /// Any other error is rethrown so the caller can surface it.
    static func perform(
        vaultID: String,
        idempotencyKey: String = UUID().uuidString,
        store: PendingCheckInStore = .shared,
        send: Send = { try await APIClient.shared.checkIn(vaultID: $0, idempotencyKey: $1) }
    ) async throws {
        do {
            try await send(vaultID, idempotencyKey)
        } catch APIError.networkUnavailable {
            try store.insert(PendingCheckIn(vaultId: vaultID, queuedAt: Date(), idempotencyKey: idempotencyKey))
        }
    }
}

import XCTest
@testable import EthosProtocol

// #790: widget / App Shortcut check-ins must carry an idempotency key and queue when offline.
final class QueuedCheckInTests: XCTestCase {

    private func makeStore() -> PendingCheckInStore {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("QueuedCheckInTests-\(UUID().uuidString).json")
        return PendingCheckInStore(fileURL: url)
    }

    func test_offlineCheckIn_isQueuedWithIdempotencyKey() async throws {
        let store = makeStore()
        try await QueuedCheckIn.perform(vaultID: "v1", idempotencyKey: "key-1", store: store) { _, _ in
            throw APIError.networkUnavailable
        }
        let queued = store.getAll()
        XCTAssertEqual(queued.count, 1, "An offline tap must be queued, not lost")
        XCTAssertEqual(queued.first?.idempotencyKey, "key-1")
    }

    func test_onlineCheckIn_sendsKeyAndDoesNotQueue() async throws {
        let store = makeStore()
        var sentKeys: [String] = []
        try await QueuedCheckIn.perform(vaultID: "v1", idempotencyKey: "key-2", store: store) { _, key in
            sentKeys.append(key)
        }
        XCTAssertEqual(sentKeys, ["key-2"])
        XCTAssertEqual(store.count, 0)
    }

    func test_repeatedIntentWithSameKey_sendsKeyEachTimeForServerDedupe() async throws {
        let store = makeStore()
        var sentKeys: [String] = []
        for _ in 0..<2 {
            try await QueuedCheckIn.perform(vaultID: "v1", idempotencyKey: "same", store: store) { _, key in
                sentKeys.append(key)
            }
        }
        XCTAssertEqual(sentKeys, ["same", "same"], "Retries must reuse the key so the server can dedupe")
    }
}

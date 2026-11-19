import XCTest
@testable import EthosProtocol

// #792: a full offline queue must refuse new check-ins visibly, never evict queued ones silently.
final class PendingCheckInCapacityTests: XCTestCase {

    private func makeStore() -> PendingCheckInStore {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("PendingCheckInCapacityTests-\(UUID().uuidString).json")
        return PendingCheckInStore(fileURL: url)
    }

    func test_insertAtCapacity_throwsQueueFull_andKeepsOldestItem() throws {
        let store = makeStore()
        for i in 0..<PendingCheckInStore.maxQueueSize {
            try store.insert(PendingCheckIn(vaultId: "v\(i)", queuedAt: Date(), idempotencyKey: "k\(i)"))
        }

        XCTAssertThrowsError(try store.insert(PendingCheckIn(vaultId: "overflow", queuedAt: Date(), idempotencyKey: "x"))) { error in
            XCTAssertEqual(error as? PendingCheckInStoreError, .queueFull)
        }

        let all = store.getAll()
        XCTAssertEqual(all.count, PendingCheckInStore.maxQueueSize)
        XCTAssertEqual(all.first?.vaultId, "v0", "The earliest queued check-in must not be evicted")
        XCTAssertFalse(all.contains { $0.vaultId == "overflow" })
    }

    func test_replacingExistingVault_atCapacity_succeeds() throws {
        let store = makeStore()
        for i in 0..<PendingCheckInStore.maxQueueSize {
            try store.insert(PendingCheckIn(vaultId: "v\(i)", queuedAt: Date(), idempotencyKey: "k\(i)"))
        }
        // Re-queuing a vault already in the queue does not grow it, so it must not be refused.
        XCTAssertNoThrow(try store.insert(PendingCheckIn(vaultId: "v5", queuedAt: Date(), idempotencyKey: "k5b")))
        XCTAssertEqual(store.count, PendingCheckInStore.maxQueueSize)
    }
}

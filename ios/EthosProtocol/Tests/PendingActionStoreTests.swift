import XCTest
@testable import EthosProtocol

// #821: PendingActionStore must enqueue, deduplicate check-ins, remove on success,
// and persist across a simulated relaunch.
final class PendingActionStoreTests: XCTestCase {

    private func makeStore() -> PendingActionStore {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("PendingActionStoreTests-\(UUID().uuidString).json")
        return PendingActionStore(fileURL: url)
    }

    private func makeAction(type: PendingActionType, vaultId: String, amount: Int64? = nil) -> PendingAction {
        PendingAction(id: UUID().uuidString, type: type, vaultId: vaultId, amount: amount, queuedAt: Date())
    }

    // MARK: - Enqueue

    func test_insert_checkIn_appearsInGetAll() throws {
        let store = makeStore()
        let action = makeAction(type: .checkIn, vaultId: "v1")
        try store.insert(action)
        XCTAssertEqual(store.getAll().count, 1)
        XCTAssertEqual(store.getAll().first?.vaultId, "v1")
    }

    func test_insert_deposit_appearsInGetAll() throws {
        let store = makeStore()
        let action = makeAction(type: .deposit, vaultId: "v1", amount: 500)
        try store.insert(action)
        XCTAssertEqual(store.getAll().count, 1)
        XCTAssertEqual(store.getAll().first?.amount, 500)
    }

    func test_count_reflectsQueueSize() throws {
        let store = makeStore()
        XCTAssertEqual(store.count, 0)
        try store.insert(makeAction(type: .checkIn, vaultId: "v1"))
        XCTAssertEqual(store.count, 1)
        try store.insert(makeAction(type: .deposit, vaultId: "v2", amount: 100))
        XCTAssertEqual(store.count, 2)
    }

    // MARK: - Deduplication

    func test_insert_secondCheckIn_forSameVault_replacesFirst() throws {
        let store = makeStore()
        let first = makeAction(type: .checkIn, vaultId: "v1")
        try store.insert(first)

        let second = PendingAction(id: UUID().uuidString, type: .checkIn, vaultId: "v1",
                                   amount: nil, queuedAt: Date().addingTimeInterval(1))
        try store.insert(second)

        let all = store.getAll()
        XCTAssertEqual(all.count, 1, "A second check-in for the same vault must replace the first, not grow the queue")
        XCTAssertEqual(all.first?.id, second.id)
    }

    func test_insert_twoDeposits_forSameVault_bothQueued() throws {
        let store = makeStore()
        try store.insert(makeAction(type: .deposit, vaultId: "v1", amount: 100))
        try store.insert(makeAction(type: .deposit, vaultId: "v1", amount: 200))

        XCTAssertEqual(store.getAll().count, 2, "Deposits for the same vault must not be deduplicated")
    }

    func test_insert_twoWithdraws_forSameVault_bothQueued() throws {
        let store = makeStore()
        try store.insert(makeAction(type: .withdraw, vaultId: "v1", amount: 50))
        try store.insert(makeAction(type: .withdraw, vaultId: "v1", amount: 75))

        XCTAssertEqual(store.getAll().count, 2, "Withdrawals for the same vault must not be deduplicated")
    }

    // MARK: - Removal after success (line 112)

    func test_delete_removesMatchingActionById() throws {
        let store = makeStore()
        let a1 = makeAction(type: .checkIn, vaultId: "v1")
        let a2 = makeAction(type: .deposit, vaultId: "v2", amount: 100)
        try store.insert(a1)
        try store.insert(a2)

        try store.delete(a1)

        let remaining = store.getAll()
        XCTAssertEqual(remaining.count, 1)
        XCTAssertEqual(remaining.first?.id, a2.id)
    }

    func test_delete_nonExistentItem_leavesQueueUnchanged() throws {
        let store = makeStore()
        let queued = makeAction(type: .checkIn, vaultId: "v1")
        try store.insert(queued)

        let phantom = makeAction(type: .checkIn, vaultId: "v9")
        try store.delete(phantom)

        XCTAssertEqual(store.count, 1)
    }

    func test_deleteAll_emptiesQueue() throws {
        let store = makeStore()
        try store.insert(makeAction(type: .checkIn, vaultId: "v1"))
        try store.insert(makeAction(type: .deposit, vaultId: "v2", amount: 10))

        try store.deleteAll()

        XCTAssertEqual(store.count, 0)
    }

    // MARK: - Persistence across simulated relaunch

    func test_persistsAcrossRelaunch() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("PendingActionStoreTests-relaunch-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }

        let action = makeAction(type: .checkIn, vaultId: "v-persist")
        let store1 = PendingActionStore(fileURL: url)
        try store1.insert(action)

        // Simulate relaunch: new instance reads from same file.
        let store2 = PendingActionStore(fileURL: url)
        let loaded = store2.getAll()
        XCTAssertEqual(loaded.count, 1)
        XCTAssertEqual(loaded.first?.vaultId, "v-persist")
        XCTAssertEqual(loaded.first?.type, .checkIn)
    }

    func test_deletedAction_notVisibleAfterRelaunch() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("PendingActionStoreTests-delete-relaunch-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }

        let action = makeAction(type: .deposit, vaultId: "v-gone", amount: 42)
        let store1 = PendingActionStore(fileURL: url)
        try store1.insert(action)
        try store1.delete(action)

        let store2 = PendingActionStore(fileURL: url)
        XCTAssertEqual(store2.count, 0)
    }
}

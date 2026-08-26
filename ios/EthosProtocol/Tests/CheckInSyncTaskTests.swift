import XCTest
@testable import EthosProtocol

// MARK: - Issue #166: crash-window duplicate-submission risk

final class CheckInSyncTaskTests: XCTestCase {

    private final class MockAPIClient: APIClientProtocol {
        var resultToReturn: CheckInResult = .success
        private(set) var idempotencyKeysUsed: [String?] = []

        func checkIn(vaultID: String, idempotencyKey: String?) async -> CheckInResult {
            idempotencyKeysUsed.append(idempotencyKey)
            return resultToReturn
        }
    }

    private func makeStore() -> PendingCheckInStore {
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("pending_checkins_test_\(UUID().uuidString).json")
        return PendingCheckInStore(fileURL: tempURL)
    }

    func test_resubmissionAfterCrash_reusesOriginalIdempotencyKey() async {
        // Simulates: the network call already succeeded, but the process died before
        // store.delete() ran, so the entry is still on disk with its original key.
        let store = makeStore()
        let item = PendingCheckIn(vaultId: "v1", queuedAt: Date(), idempotencyKey: "original-key-123")
        store.insert(item)

        let mockClient = MockAPIClient()
        mockClient.resultToReturn = .success
        let task = CheckInSyncTask.shared
        task.apiClient = mockClient
        task.store = store

        _ = await task.performSync()

        XCTAssertEqual(mockClient.idempotencyKeysUsed, ["original-key-123"])
        XCTAssertEqual(store.count, 0)
    }

    func test_corruptQueueFile_isSurfacedRatherThanSilentlyDropped() {
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("pending_checkins_corrupt_\(UUID().uuidString).json")
        // Simulate a file captured mid-write: valid UTF-8 but not valid JSON for our schema.
        try? Data("{\"vaultId\": \"v1\", \"queuedAt\"".utf8).write(to: tempURL)

        let store = PendingCheckInStore(fileURL: tempURL)
        let items = store.getAll()

        XCTAssertTrue(items.isEmpty)
        XCTAssertTrue(store.lastLoadWasCorrupted)
    }
}

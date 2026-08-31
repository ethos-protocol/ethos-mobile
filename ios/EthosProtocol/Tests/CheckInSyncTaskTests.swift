import XCTest
@testable import EthosProtocol

// MARK: - #203 CheckInSyncTask Tests
//
// See PendingCheckInStoreTests for context on the duplicate-queue regression this
// guards against (8d8d59d).

final class CheckInSyncTaskTests: XCTestCase {

    private var originalAPIClient: APIClientProtocol!
    private var originalStore: PendingCheckInStore!

    override func setUp() {
        super.setUp()
        originalAPIClient = CheckInSyncTask.shared.apiClient
        originalStore = CheckInSyncTask.shared.store
    }

    override func tearDown() {
        CheckInSyncTask.shared.apiClient = originalAPIClient
        CheckInSyncTask.shared.store = originalStore
        super.tearDown()
    }

    func test_performSync_emptyQueue_returnsSuccess() async {
        CheckInSyncTask.shared.store = makeTestStore()
        CheckInSyncTask.shared.apiClient = StubAPIClient(result: .success)

        let result = await CheckInSyncTask.shared.performSync()

        XCTAssertEqual(result, .success)
    }

    func test_performSync_success_deletesItemFromQueue() async {
        let store = makeTestStore()
        store.insert(PendingCheckIn(vaultId: "vault-1", queuedAt: Date()))
        CheckInSyncTask.shared.store = store
        CheckInSyncTask.shared.apiClient = StubAPIClient(result: .success)

        let result = await CheckInSyncTask.shared.performSync()

        XCTAssertEqual(result, .success)
        XCTAssertEqual(store.count, 0)
    }

    func test_performSync_networkUnavailable_keepsItemAndRequestsRetry() async {
        let store = makeTestStore()
        let item = PendingCheckIn(vaultId: "vault-1", queuedAt: Date())
        store.insert(item)
        CheckInSyncTask.shared.store = store
        CheckInSyncTask.shared.apiClient = StubAPIClient(result: .networkUnavailable)

        let result = await CheckInSyncTask.shared.performSync()

        XCTAssertEqual(result, .retry)
        XCTAssertEqual(store.getAll(), [item])
    }

    func test_performSync_nonRetryableServerError_dropsItem() async {
        let store = makeTestStore()
        store.insert(PendingCheckIn(vaultId: "vault-1", queuedAt: Date()))
        CheckInSyncTask.shared.store = store
        CheckInSyncTask.shared.apiClient = StubAPIClient(result: .serverError(code: 404, message: "gone"))

        let result = await CheckInSyncTask.shared.performSync()

        XCTAssertEqual(result, .success)
        XCTAssertEqual(store.count, 0,
            "A non-retryable server error (400/404/410) must drop the item, not retry forever")
    }

    func test_performSync_retryableServerError_keepsItemAndRequestsRetry() async {
        let store = makeTestStore()
        let item = PendingCheckIn(vaultId: "vault-1", queuedAt: Date())
        store.insert(item)
        CheckInSyncTask.shared.store = store
        CheckInSyncTask.shared.apiClient = StubAPIClient(result: .serverError(code: 500, message: "boom"))

        let result = await CheckInSyncTask.shared.performSync()

        XCTAssertEqual(result, .retry)
        XCTAssertEqual(store.getAll(), [item])
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private func makeTestStore() -> PendingCheckInStore {
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("CheckInSyncTaskTests-\(UUID().uuidString).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: fileURL) }
        return PendingCheckInStore(fileURL: fileURL)
    }

    private final class StubAPIClient: APIClientProtocol {
        let result: CheckInResult
        init(result: CheckInResult) { self.result = result }
        func checkIn(vaultID: String) async -> CheckInResult { result }
    }
}

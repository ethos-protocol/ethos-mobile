import XCTest
@testable import EthosProtocol

// MARK: - Issue #28: Offline Check-In Queue Tests

final class CheckInQueueTests: XCTestCase {

    private func makeQueue() -> CheckInQueue {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        return CheckInQueue(directory: dir)
    }

    func test_enqueue_addsPendingEntry() {
        let queue = makeQueue()
        queue.enqueue(vaultID: "vault-1")
        XCTAssertEqual(queue.pending.map(\.vaultID), ["vault-1"])
        XCTAssertEqual(queue.count, 1)
    }

    func test_enqueue_sameVaultTwice_doesNotDuplicate() {
        let queue = makeQueue()
        queue.enqueue(vaultID: "vault-1")
        queue.enqueue(vaultID: "vault-1")
        XCTAssertEqual(queue.count, 1)
    }

    func test_remove_dropsOnlyMatchingEntry() {
        let queue = makeQueue()
        queue.enqueue(vaultID: "vault-1")
        queue.enqueue(vaultID: "vault-2")
        queue.remove(vaultID: "vault-1")
        XCTAssertEqual(queue.pending.map(\.vaultID), ["vault-2"])
    }

    func test_removeAll_clearsQueue() {
        let queue = makeQueue()
        queue.enqueue(vaultID: "vault-1")
        queue.enqueue(vaultID: "vault-2")
        queue.removeAll()
        XCTAssertTrue(queue.pending.isEmpty)
    }

    func test_pending_isOrderedOldestFirst() {
        let queue = makeQueue()
        queue.enqueue(vaultID: "vault-1")
        queue.enqueue(vaultID: "vault-2")
        queue.enqueue(vaultID: "vault-3")
        XCTAssertEqual(queue.pending.map(\.vaultID), ["vault-1", "vault-2", "vault-3"])
    }

    func test_persistsAcrossInstances_sameDirectory() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        CheckInQueue(directory: dir).enqueue(vaultID: "vault-1")
        let reloaded = CheckInQueue(directory: dir)
        XCTAssertEqual(reloaded.pending.map(\.vaultID), ["vault-1"])
    }
}

final class CheckInSyncServiceTests: XCTestCase {

    // flush() with anything queued ends by calling
    // NotificationService.shared.{show,cancel}QueuedCheckIn(), and UNUserNotificationCenter
    // .current() traps with "bundleProxyForCurrentProcess is nil" in this bare, hostless SPM
    // test bundle in CI — same constraint as BackgroundRefreshServiceTests' notification tests.
    // Only the empty-queue case (flush() returns before touching NotificationService) is safe
    // to run there.
    private func skipIfCI() throws {
        try XCTSkipIf(ProcessInfo.processInfo.environment["CI"] != nil,
                      "UNUserNotificationCenter requires a real app host process, unavailable in CI")
    }

    private func makeService(queueDirectory: URL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)) -> (CheckInSyncService, CheckInQueue) {
        let queue = CheckInQueue(directory: queueDirectory)
        let service = CheckInSyncService()
        service.queue = queue
        return (service, queue)
    }

    func test_flush_emptyQueue_succeedsWithoutCallingProvider() async {
        let (service, _) = makeService()
        var callCount = 0
        service.checkInProvider = { _ in callCount += 1 }

        let succeeded = await service.flush()

        XCTAssertTrue(succeeded)
        XCTAssertEqual(callCount, 0)
    }

    func test_flush_successfulRetry_removesFromQueue() async throws {
        try skipIfCI()
        let (service, queue) = makeService()
        queue.enqueue(vaultID: "vault-1")
        service.checkInProvider = { _ in }

        let succeeded = await service.flush()

        XCTAssertTrue(succeeded)
        XCTAssertTrue(queue.pending.isEmpty)
    }

    func test_flush_networkStillUnavailable_leavesItemQueued() async throws {
        try skipIfCI()
        let (service, queue) = makeService()
        queue.enqueue(vaultID: "vault-1")
        service.checkInProvider = { _ in throw APIError.networkUnavailable }

        let succeeded = await service.flush()

        XCTAssertFalse(succeeded)
        XCTAssertEqual(queue.pending.map(\.vaultID), ["vault-1"])
    }

    func test_flush_serverError_leavesItemQueuedForRetry() async throws {
        try skipIfCI()
        // Mirrors Android: only a definitive rejection (vault no longer exists) drops the
        // item — a transient server error must not, since dropping a dead-man's-switch
        // check-in on a retryable failure risks the vault being released even though the
        // user did check in.
        let (service, queue) = makeService()
        queue.enqueue(vaultID: "vault-1")
        service.checkInProvider = { _ in throw APIError.serverError("boom") }

        let succeeded = await service.flush()

        XCTAssertFalse(succeeded)
        XCTAssertEqual(queue.pending.map(\.vaultID), ["vault-1"])
    }

    func test_flush_vaultNotFound_dropsFromQueue() async throws {
        try skipIfCI()
        // The one non-retryable case: the server has definitively rejected the check-in
        // because the vault no longer exists, so retrying it can never succeed.
        let (service, queue) = makeService()
        queue.enqueue(vaultID: "vault-1")
        service.checkInProvider = { _ in throw APIError.notFound }

        let succeeded = await service.flush()

        XCTAssertTrue(succeeded)
        XCTAssertTrue(queue.pending.isEmpty)
    }

    func test_flush_mixedResults_onlyRemovesSucceededAndNonRetryable() async throws {
        try skipIfCI()
        let (service, queue) = makeService()
        queue.enqueue(vaultID: "vault-succeeds")
        queue.enqueue(vaultID: "vault-not-found")
        queue.enqueue(vaultID: "vault-still-offline")
        service.checkInProvider = { vaultID in
            switch vaultID {
            case "vault-succeeds": return
            case "vault-not-found": throw APIError.notFound
            default: throw APIError.networkUnavailable
            }
        }

        let succeeded = await service.flush()

        XCTAssertFalse(succeeded)
        XCTAssertEqual(queue.pending.map(\.vaultID), ["vault-still-offline"])
    }
}

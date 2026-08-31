import BackgroundTasks
import Foundation
import os.log

// MARK: - CheckInSyncTask

/// Drains the `PendingCheckInStore` queue whenever the device has connectivity.
///
/// Mirrors the CHECK_IN path of Android's `PendingActionSyncWorker` (PendingActionSyncWorker.kt) exactly:
/// - Iterates pending items oldest-first.
/// - On `ApiResult.Success` → delete from queue.
/// - On network unavailable → mark `hasRetryableFailure`, leave item, schedule again.
/// - On server error with a NON-RETRYABLE code (400/404/410) → delete (vault is gone
///   or request is permanently invalid — retrying would never help).
/// - On any other server error (5xx, 401, etc.) → leave item for retry.
/// - Posts a local notification when all items are cleared (mirrors
///   `notificationHelper.cancelQueuedCheckIn()` on Android).
///
/// Requires `"app.ethos-protocol.checkin-sync"` in BGTaskSchedulerPermittedIdentifiers
/// (Info.plist) and `BGProcessingTaskRequest` so the OS picks it up on the next
/// background opportunity with network access.
final class CheckInSyncTask {
    static let shared = CheckInSyncTask()
    static let taskIdentifier = "app.ethos-protocol.checkin-sync"

    // #204: see docs/background-task-scheduling.md — used to compare requested vs.
    // real-world observed cadence against BackgroundRefreshService's separate scheduling pool.
    private static let log = OSLog(subsystem: "app.ethos-protocol", category: "background-scheduling")

    // Error codes where the server has definitively rejected the check-in. Matches
    // PendingActionSyncWorker.NON_RETRYABLE_ERROR_CODES on Android exactly.
    static let nonRetryableErrorCodes: Set<Int> = [400, 404]

    // HTTP 410 Gone — vault has already expired; handled separately to surface a notification.
    static let vaultExpiredCode = 410

    // Injected for testing
    var apiClient: APIClientProtocol = APIClient.shared
    var store: PendingCheckInStore = .shared

    private init() {}

    // MARK: - Registration

    /// Call once from `AppDelegate.application(_:didFinishLaunchingWithOptions:)`.
    func registerBackgroundTask() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.taskIdentifier, using: nil) { [weak self] task in
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            self?.handleSync(task: processingTask)
        }
    }

    /// Schedule a one-shot BGProcessingTask to run when connectivity is available.
    /// Safe to call repeatedly — the OS de-duplicates pending requests for the same identifier.
    func scheduleSync() {
        let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        // Submit best-effort; ignore if background tasks are disabled (simulator, low power mode).
        try? BGTaskScheduler.shared.submit(request)
        Self.log.log("scheduled: requiresNetworkConnectivity=true")
    }

    // MARK: - Sync logic

    /// Drain the queue. Called both from the BGProcessingTask handler and directly
    /// (foreground re-try when `NetworkMonitor` reports connectivity restored).
    @discardableResult
    func performSync() async -> SyncResult {
        let pending = store.getAll()
        guard !pending.isEmpty else { return .success }

        var hasRetryableFailure = false

        for item in pending {
            let result = await apiClient.checkIn(vaultID: item.vaultId)
            switch result {
            case .success:
                store.delete(item)
            case .networkUnavailable:
                hasRetryableFailure = true
            case .serverError(let code, _):
                if code == Self.vaultExpiredCode {
                    store.delete(item)
                    NotificationService.shared.showVaultExpiredNotification(vaultId: item.vaultId)
                } else if Self.nonRetryableErrorCodes.contains(code) {
                    // Server has permanently rejected this check-in — drop it.
                    store.delete(item)
                } else {
                    hasRetryableFailure = true
                }
            }
        }

        if store.count == 0 {
            NotificationService.shared.cancelQueuedCheckIn()
        }

        return hasRetryableFailure ? .retry : .success
    }

    // MARK: - BGProcessingTask handler

    private func handleSync(task: BGProcessingTask) {
        Self.log.log("invoked")
        // Re-schedule before doing the work so a gap never opens up.
        scheduleSync()

        var syncTask: Task<Void, Never>?
        task.expirationHandler = { syncTask?.cancel() }

        syncTask = Task {
            let result = await performSync()
            task.setTaskCompleted(success: result == .success)
        }
    }

    // MARK: - Result

    enum SyncResult: Equatable {
        case success
        case retry
    }
}

// MARK: - APIClientProtocol

/// Subset of APIClient used by CheckInSyncTask, extracted so tests can inject a stub
/// without subclassing APIClient. Mirrors how Android tests mock ApiClient via Hilt.
protocol APIClientProtocol: AnyObject {
    func checkIn(vaultID: String) async -> CheckInResult
}

enum CheckInResult {
    case success
    case networkUnavailable
    case serverError(code: Int, message: String)
}

// MARK: - APIClient conformance

extension APIClient: APIClientProtocol {
    func checkIn(vaultID: String) async -> CheckInResult {
        // `checkIn(vaultID:)` is ambiguous here — this extension adds a second overload
        // of the same name — so pin the reference to APIClient's original throwing/Void
        // signature via an explicitly-typed variable before calling it.
        let performCheckIn: (String) async throws -> Void = checkIn(vaultID:)
        do {
            try await performCheckIn(vaultID)
            return .success
        } catch APIError.networkUnavailable {
            return .networkUnavailable
        } catch APIError.notFound {
            return .serverError(code: 404, message: "Not found")
        } catch APIError.serverError(let msg) {
            // Parse the numeric code out of messages like "Server error 410"
            let code = msg.components(separatedBy: " ").last.flatMap(Int.init) ?? 500
            return .serverError(code: code, message: msg)
        } catch {
            return .serverError(code: 500, message: error.localizedDescription)
        }
    }
}

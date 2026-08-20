import BackgroundTasks
import Foundation

// Protocol for testable refresh task lifecycle
protocol BackgroundRefreshTask {
    var expirationHandler: (() -> Void)? { get set }
    func setTaskCompleted(success: Bool)
}

extension BGAppRefreshTask: BackgroundRefreshTask {}

// Closure for injecting dependencies during testing
typealias VaultListProvider = () async throws -> [Vault]

// Requires "app.ethos-protocol.vault-ttl-refresh" in BGTaskSchedulerPermittedIdentifiers (Info.plist).
final class BackgroundRefreshService {
    static let shared = BackgroundRefreshService()
    static let taskIdentifier = "app.ethos-protocol.vault-ttl-refresh"

    // Injected dependency for testing; defaults to APIClient.shared.listAllVaults()
    // — every page, not just the first (#21), so a TTL warning isn't missed for
    // an account with more vaults than fit on one page.
    var vaultListProvider: VaultListProvider = { try await APIClient.shared.listAllVaults() }

    // Tracks whether scheduleAppRefresh was called (for testing). `internal` (not
    // `private(set)`): HandleRefreshTests resets this between runs via `@testable import`,
    // which — like `private init()` — a `private` setter would block from another file.
    var scheduleAppRefreshCallCount = 0

    // `internal` (not `private`): lets tests construct a BackgroundRefreshService via
    // `@testable import` instead of only exercising the shared singleton, mirroring
    // APIClient's testable init.
    init() {}

    func registerBackgroundTask() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.taskIdentifier, using: nil) { [weak self] task in
            self?.handleRefresh(task: task as! BGAppRefreshTask)
        }
    }

    func scheduleAppRefresh() {
        scheduleAppRefreshCallCount += 1
        let request = BGAppRefreshTaskRequest(identifier: Self.taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 3_600) // poll every hour
        try? BGTaskScheduler.shared.submit(request)
    }

    /// Cancels the pending BGAppRefreshTaskRequest (used on sign-out, so a stale
    /// background refresh doesn't run — and re-schedule itself — for a signed-out user).
    func cancelScheduledRefresh() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
    }

    func handleRefresh(task: BackgroundRefreshTask) {
        scheduleAppRefresh()

        // Register the expiration handler before kicking off the async work so there's no
        // window where the system could expire the task before we're able to cancel it.
        // `BackgroundRefreshTask` isn't class-constrained, so the compiler won't assign through
        // the immutable `task` parameter directly even though every real conformer
        // (BGAppRefreshTask, MockBackgroundRefreshTask) is a class — shadow it with a local
        // `var` for this one synchronous mutation. The `Task { ... }` closure below captures
        // the original `task` (never mutated) instead, so it doesn't need to capture a `var`
        // across a concurrency boundary.
        var mutableTask = task
        var refreshTask: Task<Void, Never>?
        mutableTask.expirationHandler = { refreshTask?.cancel() }

        refreshTask = Task {
            do {
                let vaults = try await vaultListProvider()
                for vault in vaults where vault.status == .active {
                    if let ttl = vault.ttlRemaining, ttl < 86_400 {
                        NotificationService.shared.scheduleTTLWarning(vaultID: vault.id, ttlRemaining: ttl)
                    }
                }
                task.setTaskCompleted(success: true)
            } catch {
                task.setTaskCompleted(success: false)
            }
        }
    }
}

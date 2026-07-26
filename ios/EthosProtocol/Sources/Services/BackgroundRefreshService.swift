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

    // Injected dependency for testing; defaults to APIClient.shared.listVaults()
    var vaultListProvider: VaultListProvider = { try await APIClient.shared.listVaults() }

    // Tracks whether scheduleAppRefresh was called (for testing)
    private(set) var scheduleAppRefreshCallCount = 0

    private init() {}

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

    func handleRefresh(task: BackgroundRefreshTask) {
        scheduleAppRefresh()

        // Register the expiration handler before kicking off the async work so there's no
        // window where the system could expire the task before we're able to cancel it.
        var refreshTask: Task<Void, Never>?
        task.expirationHandler = { refreshTask?.cancel() }

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

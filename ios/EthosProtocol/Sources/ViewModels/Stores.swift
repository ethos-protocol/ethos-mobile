import Foundation
import Combine

// Runs `mutation` only if the current Task hasn't been cancelled. Guards
// @Published/@State writes that happen after an `await` — if whatever launched
// the request has since gone away and cancelled its Task (e.g. a view
// disappeared), the stale write is dropped instead of mutating state nobody is
// observing anymore. See VaultStore/AuthStore and VaultDetailView.
@MainActor
func ifNotCancelled(_ mutation: () -> Void) {
    guard !Task.isCancelled else { return }
    mutation()
}

@MainActor
final class AuthStore: ObservableObject {
    @Published var isAuthenticated = false
    @Published var isLoading = false
    @Published var error: ErrorPresentation?

    // Injected for testing; defaults to the real APIClient call. See
    // BackgroundRefreshService.vaultListProvider for the same pattern.
    var unregisterPushToken: (String) async throws -> Void = { token in
        try await APIClient.shared.unregisterPushToken(token)
    }

    init() {
        isAuthenticated = KeychainService.shared.loadToken() != nil
    }

    func signIn() async {
        isLoading = true; error = nil
        do {
            let token = try await PasskeyService.shared.authenticate()
            KeychainService.shared.saveToken(token.token)
            ifNotCancelled { isAuthenticated = true }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        // Unlike the writes above, always reset regardless of cancellation: it's
        // a loading-spinner flag, not stale request data, and leaving it true
        // would strand the UI mid-spin if this Task gets cancelled.
        isLoading = false
    }

    func register(username: String) async {
        isLoading = true; error = nil
        do {
            let credID = try await PasskeyService.shared.register(username: username)
            KeychainService.shared.saveCredentialID(credID)
            if !Task.isCancelled { await signIn() }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoading = false
    }

    func signOut() async {
        // Unregister before dropping the auth token: the request needs the
        // still-valid Bearer token to authenticate, or the server rejects it.
        if let pushToken = KeychainService.shared.loadPushToken() {
            try? await unregisterPushToken(pushToken)
            KeychainService.shared.deletePushToken()
        }
        KeychainService.shared.deleteToken()
        // Ties into #10: a subsequent user signing in on the same device must never be served
        // the previous user's cached vault data while offline.
        OfflineCache.shared.clearAll()
        isAuthenticated = false
    }
}

@MainActor
final class VaultStore: ObservableObject {
    @Published var vaults: [Vault] = []
    @Published var isLoading = false
    @Published var isLoadingMore = false
    @Published var error: ErrorPresentation?
    @Published var pendingDeepLink: UniversalLinkRouter.DeepLink?
    @Published private(set) var nextCursor: String?

    /// Whether a further page is available for VaultListView's "Load More".
    var hasMorePages: Bool { nextCursor != nil }

    func load() async {
        isLoading = true; error = nil
        if NetworkMonitor.shared.isConnected {
            await CheckInSyncService.shared.flush()
            updateQueuedIndicator()
        }
        do {
            let page = try await APIClient.shared.listVaults()
            ifNotCancelled {
                vaults = page.vaults
                nextCursor = page.nextCursor
                scheduleReminders()
            }
        } catch APIError.networkUnavailable {
            // Vaults already populated from offline cache via APIClient
            ifNotCancelled { vaultsCacheAge = APIClient.shared.vaultsCacheAge() }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoading = false
    }

    /// Fetches all vault pages via cursor-based pagination (#112) and replaces the local list.
    func loadAll(limit: Int = 20) async {
        isLoading = true; error = nil
        do {
            var accumulated: [Vault] = []
            var cursor: String? = nil
            repeat {
                let page = try await APIClient.shared.listVaults(limit: limit, after: cursor)
                accumulated.append(contentsOf: page.vaults)
                cursor = page.nextCursor
                if Task.isCancelled { return }
            } while cursor != nil
            ifNotCancelled {
                vaults = accumulated
                scheduleReminders()
            }
        } catch APIError.networkUnavailable {
            // Served from offline cache — keep whatever is already in `vaults`.
        } catch {
            ifNotCancelled { self.error = error.localizedDescription }
        }
        isLoading = false
    }

    func checkIn(vault: Vault) async {
        do {
            try await APIClient.shared.checkIn(vaultID: vault.id)
            if !Task.isCancelled { await load() }
        } catch APIError.networkUnavailable {
            // Offline: queue the check-in durably so it is retried when connectivity
            // returns, mirroring Android's VaultViewModel.checkIn → PendingCheckInDao
            // + CheckInSyncWorker pattern.
            let item = PendingCheckIn(vaultId: vault.id, queuedAt: Date())
            PendingCheckInStore.shared.insert(item)
            let count = PendingCheckInStore.shared.count
            NotificationService.shared.showQueuedCheckIn(count: count)
            CheckInSyncTask.shared.scheduleSync()
            ifNotCancelled { self.error = "Offline — check-in queued and will retry automatically" }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    /// Deposits `amount` stroops into the vault and reloads the vault list on success.
    func deposit(vault: Vault, amount: Int64) async {
        error = nil
        do {
            _ = try await APIClient.shared.deposit(vaultID: vault.id, amount: amount)
            if !Task.isCancelled { await load() }
        } catch {
            ifNotCancelled { self.error = error.localizedDescription }
        }
    }

    /// Withdraws `amount` stroops from the vault (biometric gate must be called by the UI
    /// before invoking this) and reloads the vault list on success.
    func withdraw(vault: Vault, amount: Int64) async {
        error = nil
        do {
            _ = try await APIClient.shared.withdraw(vaultID: vault.id, amount: amount)
            if !Task.isCancelled { await load() }
        } catch {
            ifNotCancelled { self.error = error.localizedDescription }
        }
    }

    /// Updates the beneficiary address for a vault (biometric gate must be called by the UI
    /// before invoking this) and reloads the vault list on success.
    func updateBeneficiary(vault: Vault, newBeneficiary: String) async {
        error = nil
        do {
            _ = try await APIClient.shared.updateBeneficiary(vaultID: vault.id, newBeneficiary: newBeneficiary)
            if !Task.isCancelled { await load() }
        } catch {
            ifNotCancelled { self.error = error.localizedDescription }
        }
        eventSocket = socket
        socket.connect(vaultID: vaultID)
    }

    func unsubscribeFromEvents() {
        eventSocket?.stop()
        eventSocket = nil
    }

    private func scheduleReminders() {
        for vault in vaults {
            guard vault.status == .active, let ttl = vault.ttlRemaining else { continue }
            NotificationService.shared.scheduleCheckInReminder(
                vaultID: vault.id, vaultName: vault.id, ttlRemaining: ttl,
                checkInInterval: vault.checkInInterval)
        }
    }
}

// MARK: - #120 Disable2FACoordinator

/// Encapsulates the two-step "authenticate then disable 2FA" sequence so it can
/// be unit-tested independently of the view layer. Both `biometric` and
/// `apiClient` are injected, letting tests supply mocks/spies.
///
/// Usage in the view:
///   ```swift
///   let coordinator = Disable2FACoordinator()
///   try await coordinator.run(vaultID: vault.id)
///   ```
struct Disable2FACoordinator {
    var biometric: BiometricAuthenticating = BiometricService.shared
    var apiDisable: (String) async throws -> Void = { id in
        try await APIClient.shared.disable2FA(vaultID: id)
    }

    /// Runs biometric authentication and, on success, calls the disable-2FA API.
    /// Throws `BiometricService.BiometricError` if authentication fails/is cancelled,
    /// or an `APIError` if the network call fails — both propagate unmodified so the
    /// call site can surface the right message.
    func run(vaultID: String) async throws {
        try await biometric.authenticate(reason: "Confirm disabling two-factor authentication")
        try await apiDisable(vaultID)
    }
}

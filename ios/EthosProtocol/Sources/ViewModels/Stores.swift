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
        do {
            let page = try await APIClient.shared.listVaults()
            ifNotCancelled {
                vaults = page.vaults
                nextCursor = page.nextCursor
                scheduleReminders()
            }
        } catch APIError.networkUnavailable {
            // Vaults already populated from offline cache via APIClient
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoading = false
    }

    /// Fetches and appends the next page. No-ops if there is no further page or
    /// a load-more is already in flight.
    func loadMore() async {
        guard let cursor = nextCursor, !isLoadingMore else { return }
        isLoadingMore = true; error = nil
        do {
            let page = try await APIClient.shared.listVaults(cursor: cursor)
            ifNotCancelled {
                vaults.append(contentsOf: page.vaults)
                nextCursor = page.nextCursor
                scheduleReminders()
            }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoadingMore = false
    }

    func checkIn(vault: Vault) async {
        do {
            try await APIClient.shared.checkIn(vaultID: vault.id)
            if !Task.isCancelled { await refreshSingle(vaultID: vault.id) }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    /// Refetches a single vault and updates it in place, instead of reloading
    /// and redecoding every vault the user owns.
    func refreshSingle(vaultID: String) async {
        do {
            let updated = try await APIClient.shared.getVault(id: vaultID)
            ifNotCancelled {
                applyUpdate(updated)
                scheduleReminder(for: updated)
            }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    func deposit(vault: Vault, amount: Int64) async {
        error = nil
        do {
            let updated = try await APIClient.shared.deposit(vaultID: vault.id, amount: amount)
            ifNotCancelled { applyUpdate(updated) }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    func withdraw(vault: Vault, amount: Int64) async {
        error = nil
        do {
            let updated = try await APIClient.shared.withdraw(vaultID: vault.id, amount: amount)
            ifNotCancelled { applyUpdate(updated) }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    func updateBeneficiary(vault: Vault, newBeneficiary: String) async {
        error = nil
        do {
            let updated = try await APIClient.shared.updateBeneficiary(vaultID: vault.id, newBeneficiary: newBeneficiary)
            ifNotCancelled { applyUpdate(updated) }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    /// Replaces a vault in the in-memory list in place (or appends it if it isn't
    /// present yet), so callers that refetch a single vault don't need to touch
    /// the rest of the list.
    func applyUpdate(_ vault: Vault) {
        if let index = vaults.firstIndex(where: { $0.id == vault.id }) {
            vaults[index] = vault
        } else {
            vaults.append(vault)
        }
    }

    // MARK: - Real-Time Events

    private var eventSocket: VaultEventSocket?

    /// Opens a real-time event stream for `vaultID` (shared/api-contract.md's
    /// WebSocket section) and applies incoming vault-updated events in place via
    /// applyUpdate(_:). If the socket can't connect after a few attempts it backs
    /// off permanently for this subscription — existing polling (pull-to-refresh,
    /// BackgroundRefreshService) never depended on the socket, so nothing else
    /// needs to change for the app to keep working. Call unsubscribeFromEvents()
    /// (e.g. from a `.task`'s cancellation) when the caller no longer needs it.
    func subscribeToEvents(vaultID: String, socket: VaultEventSocket = VaultEventSocket(baseURL: APIClient.shared.baseURL)) {
        eventSocket?.stop()
        socket.onEvent = { [weak self] event in
            guard case .vaultUpdated(let vault) = event, let self else { return }
            Task { @MainActor in
                self.applyUpdate(vault)
            }
        }
        eventSocket = socket
        socket.connect(vaultID: vaultID)
    }

    func unsubscribeFromEvents() {
        eventSocket?.stop()
        eventSocket = nil
    }

    private func scheduleReminders() {
        for vault in vaults { scheduleReminder(for: vault) }
    }

    private func scheduleReminder(for vault: Vault) {
        guard vault.status == .active, let ttl = vault.ttlRemaining else { return }
        NotificationService.shared.scheduleCheckInReminder(
            vaultID: vault.id, vaultName: vault.id, ttlRemaining: ttl, checkInInterval: vault.checkInInterval)
    }
}

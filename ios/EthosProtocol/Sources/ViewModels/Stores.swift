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
    @Published var error: String?

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
            ifNotCancelled { self.error = error.localizedDescription }
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
            ifNotCancelled { self.error = error.localizedDescription }
        }
        isLoading = false
    }

    func signOut() {
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
    @Published var error: String?
    @Published var pendingDeepLink: UniversalLinkRouter.DeepLink?
    /// How long ago the currently displayed vault list was fetched, when it came from the
    /// offline cache rather than a live request. `nil` when the last load was live (or nothing
    /// is cached yet) — lets the UI show "data as of 3 days ago" only when it's actually stale.
    @Published var vaultsCacheAge: TimeInterval?
    /// Number of check-ins queued locally because they were attempted while offline.
    @Published var queuedCheckInCount = CheckInQueue.shared.count

    func load() async {
        isLoading = true; error = nil
        if NetworkMonitor.shared.isConnected {
            await CheckInSyncService.shared.flush()
            updateQueuedIndicator()
        }
        do {
            let fetched = try await APIClient.shared.listVaults()
            ifNotCancelled {
                vaults = fetched
                vaultsCacheAge = NetworkMonitor.shared.isConnected ? nil : APIClient.shared.vaultsCacheAge()
                scheduleReminders()
            }
        } catch APIError.networkUnavailable {
            // Vaults already populated from offline cache via APIClient
            ifNotCancelled { vaultsCacheAge = APIClient.shared.vaultsCacheAge() }
        } catch {
            ifNotCancelled { self.error = error.localizedDescription }
        }
        isLoading = false
    }

    func checkIn(vault: Vault) async {
        do {
            try await APIClient.shared.checkIn(vaultID: vault.id)
            CheckInQueue.shared.remove(vaultID: vault.id)
            ifNotCancelled { updateQueuedIndicator() }
            if !Task.isCancelled { await refreshSingle(vaultID: vault.id) }
        } catch APIError.networkUnavailable {
            // Durably queue the check-in instead of losing it: this is a dead-man's-switch
            // feature, so a check-in attempted while offline must still land once connectivity
            // returns rather than silently failing. See issue #28 / Android's CheckInQueue.
            CheckInQueue.shared.enqueue(vaultID: vault.id)
            ifNotCancelled {
                updateQueuedIndicator()
                error = "Offline — check-in queued and will retry automatically"
            }
        } catch {
            ifNotCancelled { self.error = error.localizedDescription }
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
            ifNotCancelled { self.error = error.localizedDescription }
        }
    }

    func deposit(vault: Vault, amount: Int64) async {
        error = nil
        do {
            let updated = try await APIClient.shared.deposit(vaultID: vault.id, amount: amount)
            applyUpdate(updated)
        } catch { self.error = error.localizedDescription }
    }

    func withdraw(vault: Vault, amount: Int64) async {
        error = nil
        do {
            let updated = try await APIClient.shared.withdraw(vaultID: vault.id, amount: amount)
            applyUpdate(updated)
        } catch { self.error = error.localizedDescription }
    }

    func updateBeneficiary(vault: Vault, newBeneficiary: String) async {
        error = nil
        do {
            let updated = try await APIClient.shared.updateBeneficiary(vaultID: vault.id, newBeneficiary: newBeneficiary)
            applyUpdate(updated)
        } catch { self.error = error.localizedDescription }
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

    private func updateQueuedIndicator() {
        let count = CheckInQueue.shared.count
        queuedCheckInCount = count
        if count > 0 {
            NotificationService.shared.showQueuedCheckIn(count: count)
        } else {
            NotificationService.shared.cancelQueuedCheckIn()
        }
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

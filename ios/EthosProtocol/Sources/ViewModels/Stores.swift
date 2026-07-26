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
        isAuthenticated = false
    }
}

@MainActor
final class VaultStore: ObservableObject {
    @Published var vaults: [Vault] = []
    @Published var isLoading = false
    @Published var error: String?
    @Published var pendingDeepLink: UniversalLinkRouter.DeepLink?

    func load() async {
        isLoading = true; error = nil
        do {
            let fetched = try await APIClient.shared.listVaults()
            ifNotCancelled {
                vaults = fetched
                scheduleReminders()
            }
        } catch APIError.networkUnavailable {
            // Vaults already populated from offline cache via APIClient
        } catch {
            ifNotCancelled { self.error = error.localizedDescription }
        }
        isLoading = false
    }

    func checkIn(vault: Vault) async {
        do {
            try await APIClient.shared.checkIn(vaultID: vault.id)
            if !Task.isCancelled { await load() }
        } catch {
            ifNotCancelled { self.error = error.localizedDescription }
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
    }

    private func scheduleReminders() {
        for vault in vaults { scheduleReminder(for: vault) }
    }

    private func scheduleReminder(for vault: Vault) {
        guard vault.status == .active, let ttl = vault.ttlRemaining else { return }
        NotificationService.shared.scheduleCheckInReminder(
            vaultID: vault.id, vaultName: vault.id, ttlRemaining: ttl)
    }
}

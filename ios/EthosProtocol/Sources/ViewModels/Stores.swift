import Foundation
import Combine

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
            isAuthenticated = true
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    func register(username: String) async {
        isLoading = true; error = nil
        do {
            let credID = try await PasskeyService.shared.register(username: username)
            KeychainService.shared.saveCredentialID(credID)
            await signIn()
        } catch {
            self.error = error.localizedDescription
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
            vaults = try await APIClient.shared.listVaults()
            scheduleReminders()
        } catch APIError.networkUnavailable {
            // Vaults already populated from offline cache via APIClient
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    func checkIn(vault: Vault) async {
        do {
            try await APIClient.shared.checkIn(vaultID: vault.id)
            await refreshSingle(vaultID: vault.id)
        } catch { self.error = error.localizedDescription }
    }

    /// Refetches a single vault and updates it in place, instead of reloading
    /// and redecoding every vault the user owns.
    func refreshSingle(vaultID: String) async {
        do {
            let updated = try await APIClient.shared.getVault(id: vaultID)
            applyUpdate(updated)
            scheduleReminder(for: updated)
        } catch { self.error = error.localizedDescription }
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

    private func scheduleReminders() {
        for vault in vaults { scheduleReminder(for: vault) }
    }

    private func scheduleReminder(for vault: Vault) {
        guard vault.status == .active, let ttl = vault.ttlRemaining else { return }
        NotificationService.shared.scheduleCheckInReminder(
            vaultID: vault.id, vaultName: vault.id, ttlRemaining: ttl)
    }
}

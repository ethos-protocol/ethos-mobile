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

    // How long before AuthToken.expiresAt to proactively refresh (#3), and how long to
    // wait before retrying after a transient (non-auth) refresh failure.
    private let refreshLeadTime: TimeInterval = 60
    private let refreshRetryDelay: TimeInterval = 30
    private var refreshTask: Task<Void, Never>?

    // Injectable seams for testing (mirrors BackgroundRefreshService.vaultListProvider):
    // ASAuthorizationController-driven ceremonies can't run in a unit test, so AuthStore's
    // own orchestration (single ceremony on register, proactive refresh scheduling,
    // delete-and-reauth fallback) is exercised against these instead.
    var passkeyRegister: (String) async throws -> AuthToken = { try await PasskeyService.shared.register(username: $0) }
    var passkeyAuthenticate: () async throws -> AuthToken = { try await PasskeyService.shared.authenticate() }
    var refreshToken: () async throws -> AuthToken = { try await APIClient.shared.refreshToken() }

    init() {
        isAuthenticated = KeychainService.shared.loadToken() != nil
        if isAuthenticated, let expiresAt = KeychainService.shared.loadTokenExpiry() {
            scheduleRefresh(before: expiresAt)
        }
    }

    func signIn() async {
        isLoading = true; error = nil
        do {
            let token = try await passkeyAuthenticate()
            KeychainService.shared.saveToken(token.token, expiresAt: token.expiresAt)
            ifNotCancelled {
                isAuthenticated = true
                scheduleRefresh(before: token.expiresAt)
            }
        } catch {
            ifNotCancelled { self.error = error.localizedDescription }
        }
        // Unlike the writes above, always reset regardless of cancellation: it's
        // a loading-spinner flag, not stale request data, and leaving it true
        // would strand the UI mid-spin if this Task gets cancelled.
        isLoading = false
    }

    // A single passkey ceremony: PasskeyService.register() already registers with the
    // backend (which returns a session token directly) and persists the credential ID
    // atomically with that call (#4) — there is no second, redundant authenticate()
    // ceremony / biometric prompt here (#2).
    func register(username: String) async {
        isLoading = true; error = nil
        do {
            let token = try await passkeyRegister(username)
            KeychainService.shared.saveToken(token.token, expiresAt: token.expiresAt)
            ifNotCancelled {
                isAuthenticated = true
                scheduleRefresh(before: token.expiresAt)
            }
        } catch {
            ifNotCancelled { self.error = error.localizedDescription }
        }
        isLoading = false
    }

    func signOut() {
        refreshTask?.cancel()
        refreshTask = nil
        KeychainService.shared.deleteToken()
        isAuthenticated = false
    }

    // MARK: - Token refresh (#3)
    //
    // AuthToken.expiresAt was decoded but never read; a 401 was handled purely
    // reactively by deleting the token and forcing a full passkey re-authentication,
    // which can interrupt the user mid-task (e.g. mid check-in). This schedules a
    // proactive refresh shortly before expiry instead. The reactive delete-and-reauth
    // behavior in APIClient.execute() remains as-is and is the fallback used here
    // whenever the refresh call itself is rejected by the server.

    private func scheduleRefresh(before expiresAt: Date) {
        scheduleRefresh(at: expiresAt.addingTimeInterval(-refreshLeadTime))
    }

    private func scheduleRefresh(at fireDate: Date) {
        refreshTask?.cancel()
        let delay = max(0, fireDate.timeIntervalSinceNow)
        refreshTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.performRefresh()
        }
    }

    private func performRefresh() async {
        do {
            let token = try await refreshToken()
            KeychainService.shared.saveToken(token.token, expiresAt: token.expiresAt)
            ifNotCancelled { scheduleRefresh(before: token.expiresAt) }
        } catch APIError.unauthorized {
            // The server rejected the token outright — APIClient.execute() already
            // deleted it locally. Fall back to forcing a full re-authentication.
            ifNotCancelled { isAuthenticated = false }
        } catch {
            // Transient failure (e.g. offline) — retry later rather than signing the
            // user out over a network hiccup.
            ifNotCancelled { scheduleRefresh(at: Date().addingTimeInterval(refreshRetryDelay)) }
        }
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

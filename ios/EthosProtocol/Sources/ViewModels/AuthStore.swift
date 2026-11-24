import Foundation
import Combine
import SwiftUI

@MainActor
final class AuthStore: ObservableObject {
    @Published var isAuthenticated = false
    @Published var isLoading = false
    @Published var error: ErrorPresentation?
    @Published var isLocked = false
    @Published var showPINSetup = false

    // Injected for testing; defaults to the real APIClient call. See
    // BackgroundRefreshService.vaultListProvider for the same pattern.
    var unregisterPushToken: (String) async throws -> Void = { token in
        try await APIClient.shared.unregisterPushToken(token)
    }

    // How long before AuthToken.expiresAt to proactively refresh (#3), and how long to
    // wait before retrying after a transient (non-auth) refresh failure.
    private let refreshLeadTime: TimeInterval = 60
    private let refreshRetryDelay: TimeInterval = 30
    private var refreshTask: Task<Void, Never>?
    private var backgroundedAt: Date?

    // Injectable seams for testing (mirrors BackgroundRefreshService.vaultListProvider):
    // ASAuthorizationController-driven ceremonies can't run in a unit test, so AuthStore's
    // own orchestration (single ceremony on register, proactive refresh scheduling,
    // delete-and-reauth fallback) is exercised against these instead.
    // Registration now also yields the server-issued opaque WebAuthn user handle
    // (#624). It is persisted so the same handle is reused for every subsequent
    // ceremony on this account, rather than one derived from the username.
    var passkeyRegister: (String) async throws -> PasskeyRegistrationResult = {
        try await PasskeyService.shared.register(username: $0)
    }
    var passkeyAuthenticate: () async throws -> AuthToken = { try await PasskeyService.shared.authenticate() }
    var refreshToken: () async throws -> AuthToken = { try await APIClient.shared.refreshToken() }
    var linkAdditionalPasskey: (String, AccountRecoveryProof) async throws -> String = { username, proof in
        try await PasskeyService.shared.linkAdditionalPasskey(username: username, existingAccountProof: proof)
    }

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
                BiometricTimeoutIndicatorService.shared.startTimeout()
                scheduleRefresh(before: token.expiresAt)
            }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
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
            let result = try await passkeyRegister(username)
            // Persist the opaque user handle before anything else so it survives a
            // crash between registration and the first authenticated request.
            KeychainService.shared.saveUserHandle(result.userHandle)
            let token = result.token
            KeychainService.shared.saveToken(token.token, expiresAt: token.expiresAt)
            ifNotCancelled {
                isAuthenticated = true
                BiometricTimeoutIndicatorService.shared.startTimeout()
                scheduleRefresh(before: token.expiresAt)
            }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoading = false
    }

    /// Links a newly created passkey to an existing vault-owning account (for a user who
    /// lost their original device), then signs in with it. `linkAdditionalPasskey` only
    /// attaches the credential server-side and returns no session — a normal passkey
    /// authenticate() ceremony against the freshly-linked credential is what actually
    /// establishes the session, mirroring signIn().
    func recoverAccess(email: String, backupCode: String, username: String) async {
        isLoading = true; error = nil
        do {
            _ = try await linkAdditionalPasskey(username, AccountRecoveryProof(email: email, backupCode: backupCode))
            let token = try await passkeyAuthenticate()
            KeychainService.shared.saveToken(token.token, expiresAt: token.expiresAt)
            ifNotCancelled {
                isAuthenticated = true
                BiometricTimeoutIndicatorService.shared.startTimeout()
                scheduleRefresh(before: token.expiresAt)
            }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoading = false
    }

    func signOut() async {
        refreshTask?.cancel()
        refreshTask = nil
        BiometricTimeoutIndicatorService.shared.stopTimeout()
        // Unregister before dropping the auth token: the request needs the
        // still-valid Bearer token to authenticate, or the server rejects it.
        if let pushToken = KeychainService.shared.loadPushToken() {
            try? await unregisterPushToken(pushToken)
            KeychainService.shared.deletePushToken()
        }
        KeychainService.shared.deleteToken()
        // The passkey credential ID and its iCloud-synced vault association both belong to
        // this account — a subsequent user signing in on the same device must not inherit
        // them.
        KeychainService.shared.deleteCredentialID()
        // The opaque WebAuthn user handle (#624) is account-scoped too — clear it so a
        // subsequent user on this device never reuses the previous account's handle.
        KeychainService.shared.deleteUserHandle()
        ICloudSyncService.shared.clearLocalAssociations()
        // Ties into #10: a subsequent user signing in on the same device must never be served
        // the previous user's cached vault data while offline.
        OfflineCache.shared.clearAll()
        isAuthenticated = false
        isLocked = false
        backgroundedAt = nil
    }

    /// Called from RootView's `.onChange(of: scenePhase)`. Records when the app leaves the
    /// foreground and, once it returns, re-locks the vault behind a fresh biometric check if
    /// it was backgrounded for at least the configured re-lock timeout. `now:` is injectable
    /// so tests can simulate elapsed time without real waits.
    func handleScenePhaseChange(_ phase: ScenePhase, now: Date = Date()) {
        switch phase {
        case .background:
            backgroundedAt = now
            BiometricTimeoutIndicatorService.shared.stopTimeout()
        case .active:
            if let backgroundedAt, isAuthenticated,
               now.timeIntervalSince(backgroundedAt) >= ReLockTimeoutOption.current.seconds {
                isLocked = true
                BiometricTimeoutIndicatorService.shared.stopTimeout()
            } else if isAuthenticated && !isLocked {
                BiometricTimeoutIndicatorService.shared.startTimeout()
            }
            backgroundedAt = nil
        case .inactive:
            break
        @unknown default:
            break
        }
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

/// How long the app can sit in the background before `AuthStore` requires biometrics again.
/// Persisted so the choice survives relaunch; configurable from SettingsView.
enum ReLockTimeoutOption: Int, CaseIterable, Identifiable {
    case immediately = 0
    case thirtySeconds = 30
    case oneMinute = 60
    case fiveMinutes = 300
    case fifteenMinutes = 900
    case never = -1

    var id: Int { rawValue }

    var seconds: TimeInterval {
        self == .never ? .infinity : TimeInterval(rawValue)
    }

    var label: String {
        switch self {
        case .immediately:    return "Immediately"
        case .thirtySeconds:  return "30 Seconds"
        case .oneMinute:      return "1 Minute"
        case .fiveMinutes:    return "5 Minutes"
        case .fifteenMinutes: return "15 Minutes"
        case .never:          return "Never"
        }
    }

    private static let userDefaultsKey = "com.ethosprotocol.relock_timeout"

    static var current: ReLockTimeoutOption {
        get {
            guard let stored = UserDefaults.standard.object(forKey: userDefaultsKey) as? Int,
                  let option = ReLockTimeoutOption(rawValue: stored) else {
                return .oneMinute
            }
            return option
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: userDefaultsKey) }
    }
}

import Foundation
import Combine
import SwiftUI

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
    // Re-lock gate, separate from isAuthenticated: the passkey session stays signed in,
    // but the vault contents are hidden behind a fresh biometric check after the app has
    // spent long enough in the background. See handleScenePhaseChange(_:now:).
    @Published var isLocked = false

    private var backgroundedAt: Date?

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
        // Clears every piece of local state that could leak the previous user's vault
        // data to whoever uses the app next on this device (shared/resold-device threat
        // model) — not just the auth token.
        KeychainService.shared.deleteToken()
        KeychainService.shared.deleteCredentialID()
        NotificationService.shared.removeAllPendingNotifications()
        BackgroundRefreshService.shared.cancelScheduledRefresh()
        OfflineCache.shared.clearAll()
        ICloudSyncService.shared.clearLocalAssociations()
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
        case .active:
            if let backgroundedAt, isAuthenticated,
               now.timeIntervalSince(backgroundedAt) >= ReLockTimeoutOption.current.seconds {
                isLocked = true
            }
            backgroundedAt = nil
        case .inactive:
            break
        @unknown default:
            break
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

    private func scheduleReminders() {
        for vault in vaults where vault.status == .active {
            if let ttl = vault.ttlRemaining {
                NotificationService.shared.scheduleCheckInReminder(
                    vaultID: vault.id, vaultName: vault.id, ttlRemaining: ttl, checkInInterval: vault.checkInInterval)
            }
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

import Foundation
import Combine
import SwiftUI
import WidgetKit

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
    @Published var isLocked = false

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
    var passkeyRegister: (String) async throws -> AuthToken = { try await PasskeyService.shared.register(username: $0) }
    var passkeyAuthenticate: () async throws -> AuthToken = { try await PasskeyService.shared.authenticate() }
    var refreshToken: () async throws -> AuthToken = { try await APIClient.shared.refreshToken() }
    var linkAdditionalPasskey: (String, AccountRecoveryProof) async throws -> String = { username, proof in
        try await PasskeyService.shared.linkAdditionalPasskey(username: username, existingAccountProof: proof)
    }
    // #214: Used to warn before sign-out when this appears to be the account's only
    // registered passkey — the same signal registration already uses to populate
    // excludedCredentials.
    var fetchExistingCredentialCount: () async throws -> Int = {
        try await APIClient.shared.getChallenge().existingCredentialIds.count
    }

    // #212: Client-side rate limiting for recovery-code submission, reusing #119's
    // escalating cooldown schedule — a recovery backup code is just as brute-forceable
    // as an OTP.
    @Published private(set) var recoveryFailureCount: Int = 0
    @Published private(set) var recoveryCooldownSecondsRemaining: Int = 0
    var isRecoveryBlocked: Bool { recoveryCooldownSecondsRemaining > 0 }
    private var recoveryCooldownTask: Task<Void, Never>?

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
            let token = try await passkeyRegister(username)
            KeychainService.shared.saveToken(token.token, expiresAt: token.expiresAt)
            ifNotCancelled {
                isAuthenticated = true
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
        guard !isRecoveryBlocked else { return }
        isLoading = true; error = nil
        do {
            _ = try await linkAdditionalPasskey(username, AccountRecoveryProof(email: email, backupCode: backupCode))
            let token = try await passkeyAuthenticate()
            KeychainService.shared.saveToken(token.token, expiresAt: token.expiresAt)
            ifNotCancelled {
                isAuthenticated = true
                scheduleRefresh(before: token.expiresAt)
            }
            resetRecoveryRateLimit()
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
            recordRecoveryFailure()
        }
        isLoading = false
    }

    // MARK: - Recovery rate limiting (#212)

    private func recordRecoveryFailure() {
        recoveryFailureCount += 1
        let cooldown = OTPRateLimiter.cooldownSeconds(for: recoveryFailureCount)
        guard cooldown > 0 else { return }
        startRecoveryCooldown(seconds: cooldown)
    }

    private func resetRecoveryRateLimit() {
        recoveryFailureCount = 0
        recoveryCooldownSecondsRemaining = 0
        recoveryCooldownTask?.cancel()
        recoveryCooldownTask = nil
    }

    private func startRecoveryCooldown(seconds: Int) {
        recoveryCooldownTask?.cancel()
        recoveryCooldownSecondsRemaining = seconds
        recoveryCooldownTask = Task { [weak self] in
            for _ in 0..<seconds {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard !Task.isCancelled else { return }
                await self?.tickRecoveryCooldown()
            }
        }
    }

    private func tickRecoveryCooldown() {
        guard recoveryCooldownSecondsRemaining > 0 else { return }
        recoveryCooldownSecondsRemaining -= 1
    }

    /// Whether this device's passkey appears to be the only one registered to the
    /// account (#214). Callers should confirm with the user before signing out when
    /// this is true — with no other device's passkey and no recovery already in hand,
    /// signing out here could permanently lock them out of a vault holding real funds.
    /// Best-effort: a failed lookup (e.g. offline) doesn't block sign-out, so it
    /// defaults to `false` rather than trapping the user in the app.
    func isLastRemainingPasskey() async -> Bool {
        (try? await fetchExistingCredentialCount()).map { $0 <= 1 } ?? false
    }

    func signOut() async {
        refreshTask?.cancel()
        refreshTask = nil
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

/// Backs SessionsView (#208): the list of devices currently holding a valid JWT for this
/// account, plus the "Sign out this device" / "Sign out all other devices" actions. Both
/// mutating actions are expected to be gated behind a biometric prompt by the caller (the
/// view), same as VaultStore.withdraw — this store just performs the already-authorized action.
@MainActor
final class SessionsStore: ObservableObject {
    @Published var sessions: [Session] = []
    @Published var isLoading = false
    @Published var error: ErrorPresentation?

    // Injected for testing; defaults to the real APIClient calls.
    var listSessions: () async throws -> [Session] = { try await APIClient.shared.listSessions() }
    var revokeSession: (String) async throws -> Void = { try await APIClient.shared.revokeSession(id: $0) }
    var revokeOtherSessions: () async throws -> Void = { try await APIClient.shared.revokeOtherSessions() }

    func load() async {
        isLoading = true; error = nil
        do {
            sessions = try await listSessions()
        } catch {
            self.error = ErrorPresentation(error)
        }
        isLoading = false
    }

    /// Signs out the device behind `session`. Removes it from the local list immediately on
    /// success rather than requiring a full reload.
    func revoke(_ session: Session) async {
        error = nil
        do {
            try await revokeSession(session.id)
            sessions.removeAll { $0.id == session.id }
        } catch {
            self.error = ErrorPresentation(error)
        }
    }

    /// Signs out every device except the current one, then reloads so the list reflects the
    /// server's view (rather than assuming every non-current session was in `sessions`).
    func revokeAllOthers() async {
        error = nil
        do {
            try await revokeOtherSessions()
            await load()
        } catch {
            self.error = ErrorPresentation(error)
        }
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
    /// How long ago the currently-displayed `vaults` were fetched, when served from the
    /// offline cache (APIClient.vaultsCacheAge()) rather than a fresh network response.
    @Published var vaultsCacheAge: TimeInterval?
    /// Mirrors PendingCheckInStore's count for while the app is foregrounded — drives the
    /// in-app "N check-ins queued" banner alongside NotificationService's queued indicator.
    @Published private(set) var queuedCheckInCount = 0
    @Published private(set) var queueAtCapacity = false

    private var eventSocket: VaultEventSocket?

    /// Whether a further page is available for VaultListView's "Load More".
    var hasMorePages: Bool { nextCursor != nil }

    // #249: Injectable seam for the WidgetKit reload call — lets unit tests assert that
    // reloadTimelines(ofKind:) fires without instantiating a real WidgetCenter.
    // Production code leaves this as the default real WidgetCenter call.
    var widgetReloader: (String) -> Void = { kind in
        WidgetCenter.shared.reloadTimelines(ofKind: kind)
    }

    private func updateQueuedIndicator() {
        queuedCheckInCount = PendingCheckInStore.shared.count
        queueAtCapacity = PendingCheckInStore.shared.isAtCapacity
    }

    func load() async {
        isLoading = true; error = nil
        if NetworkMonitor.shared.isConnected {
            await CheckInSyncTask.shared.performSync()
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

    /// Fetches the next page after `nextCursor` and appends it to `vaults`, for
    /// VaultListView's "Load More" row. No-ops if a fetch is already in flight or
    /// there is no further page.
    func loadMore() async {
        guard !isLoadingMore, let cursor = nextCursor else { return }
        isLoadingMore = true; error = nil
        do {
            let page = try await APIClient.shared.listVaults(cursor: cursor)
            ifNotCancelled {
                vaults += page.vaults
                nextCursor = page.nextCursor
            }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoadingMore = false
    }

    /// Fetches all vault pages via cursor-based pagination (#112) and replaces the local list.
    func loadAll(limit: Int = 20) async {
        isLoading = true; error = nil
        do {
            var accumulated: [Vault] = []
            var cursor: String? = nil
            repeat {
                let page = try await APIClient.shared.listVaults(cursor: cursor, limit: limit)
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
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoading = false
    }

    func checkIn(vault: Vault) async {
        do {
            // `checkIn(vaultID:)` is ambiguous between APIClient's original throwing/Void
            // signature and the CheckInSyncTask.APIClientProtocol conformance's overload —
            // pin the reference to the original before calling it.
            let performCheckIn: (String) async throws -> Void = APIClient.shared.checkIn(vaultID:)
            try await performCheckIn(vault.id)
            if !Task.isCancelled { await load() }
        } catch APIError.networkUnavailable {
            // Offline: queue the check-in durably so it is retried when connectivity
            // returns, mirroring Android's VaultViewModel.checkIn → PendingCheckInDao
            // + CheckInSyncWorker pattern.
            let item = PendingCheckIn(vaultId: vault.id, queuedAt: Date())
            PendingCheckInStore.shared.insert(item)
            let count = PendingCheckInStore.shared.count
            let atCapacity = PendingCheckInStore.shared.isAtCapacity
            NotificationService.shared.showQueuedCheckIn(count: count)
            CheckInSyncTask.shared.scheduleSync()
            ifNotCancelled {
                queuedCheckInCount = count
                queueAtCapacity = atCapacity
                let message = atCapacity
                    ? "Offline — queue is full (oldest check-in replaced). Will retry automatically."
                    : "Offline — check-in queued and will retry automatically"
                self.error = ErrorPresentation(message: message)
            }
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
            ifNotCancelled { self.error = ErrorPresentation(error) }
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
            ifNotCancelled { self.error = ErrorPresentation(error) }
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
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    /// Sets or clears (via `label: nil`) a vault's display label (#218), then
    /// reloads the vault list so the new label shows up in VaultRowView.
    func updateLabel(vault: Vault, label: String?) async {
        error = nil
        do {
            _ = try await APIClient.shared.updateVaultLabel(vaultID: vault.id, label: label)
            if !Task.isCancelled { await load() }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    /// Replaces the vault matching `updated.id` in place (preserving list order), or
    /// appends it if it isn't currently in `vaults` — e.g. a vault created from another
    /// device that this session hasn't loaded yet.
    func applyUpdate(_ updated: Vault) {
        if let index = vaults.firstIndex(where: { $0.id == updated.id }) {
            vaults[index] = updated
        } else {
            vaults.append(updated)
        }
    }

    /// Subscribes to real-time vault events (#20) and applies incoming updates to
    /// `vaults` in place, so balance/status changes made from another device show up
    /// without waiting for the next poll.
    func subscribeToEvents(vaultID: String, socket: VaultEventSocket) {
        eventSocket = socket
        socket.onEvent = { [weak self] event in
            guard let self else { return }
            switch event {
            case .vaultUpdated(let updated):
                self.applyUpdate(updated)
                // #249: Nudge the widget to refresh immediately rather than waiting for its
                // next scheduled timeline tick. Only reloads timelines when the app is in
                // the foreground (the socket is only active then), so WidgetKit budget is
                // not spent on background wakeups.
                self.widgetReloader("TTLWidget")
            case .vaultExpired, .vaultReleased:
                // Neither payload carries the full vault, and both change status (and, for
                // a release, the balance) — refetch rather than patching fields locally.
                Task { await self.load() }
            case .ping:
                // Server keepalive — no state change.
                break
            case .error(let code, let message):
                self.error = ErrorPresentation(message: "Vault event stream error (\(code)): \(message)")
            case .unknown:
                break
            }
        }
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

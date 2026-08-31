import Foundation

// MARK: - #202 Pending 2FA Session Persistence

/// Persisted state for a 2FA setup/verification flow that's in progress but not yet
/// completed, keyed by vault ID.
///
/// Only the method and a "code sent" flag are persisted — never the OTP the user types,
/// and never the TOTP secret/provisioning URI (those are re-fetched by re-running setup
/// if truly needed; the verify step doesn't require them to accept a code).
struct PendingTwoFactorSession: Codable, Equatable {
    let method: TwoFactorMethod
    let codeSent: Bool
    let createdAt: Date
}

/// Restores the in-progress 2FA method and "code sent" flag across app relaunches
/// (#202), so process death mid-verification (SMS/email code already sent, or TOTP
/// secret already generated) doesn't force the user to restart the whole flow.
@MainActor
final class PendingTwoFactorSessionStore {
    static let shared = PendingTwoFactorSessionStore()

    /// A pending session older than this is considered stale — the underlying OTP has
    /// almost certainly expired server-side — and is treated as if it didn't exist.
    static let maxAge: TimeInterval = 10 * 60

    private let defaults: UserDefaults
    private let now: () -> Date
    private static let key = "com.ethosprotocol.pendingTwoFactorSessions"

    init(defaults: UserDefaults = .standard, now: @escaping () -> Date = { Date() }) {
        self.defaults = defaults
        self.now = now
    }

    /// Returns the pending session for `vaultID`, or `nil` if there is none or it has expired.
    func session(for vaultID: String) -> PendingTwoFactorSession? {
        guard let session = allSessions()[vaultID] else { return nil }
        guard now().timeIntervalSince(session.createdAt) < Self.maxAge else {
            clear(for: vaultID)
            return nil
        }
        return session
    }

    func save(_ session: PendingTwoFactorSession, for vaultID: String) {
        var sessions = allSessions()
        sessions[vaultID] = session
        persist(sessions)
    }

    func clear(for vaultID: String) {
        var sessions = allSessions()
        sessions.removeValue(forKey: vaultID)
        persist(sessions)
    }

    private func allSessions() -> [String: PendingTwoFactorSession] {
        guard let data = defaults.data(forKey: Self.key),
              let sessions = try? JSONDecoder().decode([String: PendingTwoFactorSession].self, from: data)
        else { return [:] }
        return sessions
    }

    private func persist(_ sessions: [String: PendingTwoFactorSession]) {
        guard let data = try? JSONEncoder().encode(sessions) else { return }
        defaults.set(data, forKey: Self.key)
    }
}

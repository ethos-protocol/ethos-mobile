import Foundation
import Combine

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

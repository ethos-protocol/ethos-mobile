import Foundation

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

import LocalAuthentication
import Foundation

/// Abstraction over biometric / device-passcode authentication.
/// Conformed to by `BiometricService` for production and by test doubles in unit tests.
protocol BiometricAuthenticating: AnyObject {
    func authenticate(reason: String) async throws
}

final class BiometricService: BiometricAuthenticating {
    static let shared = BiometricService()
    private init() {}

    enum BiometricError: LocalizedError {
        case authenticationFailed
        case userCancelled
        case notAvailable

        var errorDescription: String? {
            switch self {
            case .authenticationFailed: return "Biometric authentication failed. Please try again."
            case .userCancelled:        return "Authentication was cancelled."
            case .notAvailable:         return "No authentication method is available on this device."
            }
        }
    }

    /// Authenticates with Face ID or Touch ID, falling back to device passcode if biometry is unavailable.
    func authenticate(reason: String) async throws {
        let context = LAContext()
        var biometryError: NSError?

        let policy: LAPolicy = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &biometryError)
            ? .deviceOwnerAuthenticationWithBiometrics
            : .deviceOwnerAuthentication

        guard context.canEvaluatePolicy(policy, error: &biometryError) else {
            throw BiometricError.notAvailable
        }

        do {
            let success = try await context.evaluatePolicy(policy, localizedReason: reason)
            if !success { throw BiometricError.authenticationFailed }
        } catch let error as LAError {
            switch error.code {
            case .userCancel, .userFallback, .appCancel, .systemCancel:
                throw BiometricError.userCancelled
            default:
                throw BiometricError.authenticationFailed
            }
        }
    }
}

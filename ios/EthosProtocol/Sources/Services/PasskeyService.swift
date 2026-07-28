import AuthenticationServices
import Foundation

final class PasskeyService: NSObject {
    static let shared = PasskeyService()
    private override init() {}

    private static let relyingPartyIdentifier = "ethos-protocol.app"

    func register(username: String) async throws -> String {
        let credential = try await createRegistrationCredential(username: username)
        try await APIClient.shared.registerPasskey(
            credentialID: credential.credentialID,
            publicKey: credential.publicKey,
            clientDataJSON: credential.clientDataJSON
        )
        let credential = try await performRequest(request)
        guard let reg = credential as? ASAuthorizationPlatformPublicKeyCredentialRegistration else {
            throw PasskeyError.registrationFailed
        }
        let credID = reg.credentialID.base64URLEncodedString()
        // Send the raw WebAuthn attestation object — not the public key itself.
        // The backend extracts the COSE-encoded public key from this object during
        // verification. Field name is `attestation_object` per shared/api-contract.md.
        let attestationObject = reg.rawAttestationObject?.base64URLEncodedString() ?? ""
        let clientData = reg.rawClientDataJSON.base64URLEncodedString()
        try await APIClient.shared.registerPasskey(credentialID: credID, attestationObject: attestationObject, clientDataJSON: clientData)
        return credID
    }

    func authenticate() async throws -> AuthToken {
        let challenge = try await APIClient.shared.getChallenge()
        guard let challengeData = Data(base64URLEncoded: challenge.challenge) else {
            throw PasskeyError.authenticationFailed
        }
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: Self.relyingPartyIdentifier)
        let request = provider.createCredentialAssertionRequest(challenge: challengeData)
        let credential: ASAuthorizationCredential
        do {
            credential = try await performRequest(request)
        } catch {
            throw PasskeyError.map(error, fallback: .authenticationFailed)
        }
        guard let assertion = credential as? ASAuthorizationPlatformPublicKeyCredentialAssertion else {
            throw PasskeyError.authenticationFailed
        }
        let credID = assertion.credentialID.base64URLEncodedString()
        let clientData = assertion.rawClientDataJSON.base64URLEncodedString()
        let signature = assertion.signature.base64URLEncodedString()
        return try await APIClient.shared.verifyPasskey(credentialID: credID, clientDataJSON: clientData, signature: signature)
    }

    /// Registers a new passkey on this device and links it to an existing vault-owning
    /// account, for a user who lost their original device and thus their platform
    /// passkey. `existingAccountProof` must already have been verified by the caller
    /// (e.g. via a "lost your device?" recovery flow) before this is invoked.
    func linkAdditionalPasskey(username: String, existingAccountProof proof: AccountRecoveryProof) async throws -> String {
        let credential = try await createRegistrationCredential(username: username)
        try await APIClient.shared.linkAdditionalPasskey(
            existingAccountProof: proof,
            credentialID: credential.credentialID,
            publicKey: credential.publicKey,
            clientDataJSON: credential.clientDataJSON
        )
        return credential.credentialID
    }

    private struct RegistrationCredential {
        let credentialID: String
        let publicKey: String
        let clientDataJSON: String
    }

    private func createRegistrationCredential(username: String) async throws -> RegistrationCredential {
        let challenge = try await APIClient.shared.getChallenge()
        guard let challengeData = Data(base64URLEncoded: challenge.challenge) else {
            throw PasskeyError.registrationFailed
        }
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: Self.relyingPartyIdentifier)
        let request = provider.createCredentialRegistrationRequest(
            challenge: challengeData,
            name: username,
            userID: Data(username.utf8)
        )
        // Without this, a user (or anyone with physical access) can register a second,
        // independent passkey for an account that already has one on this device — the
        // system stays silent instead of warning that a credential already exists.
        request.excludedCredentials = Self.excludedCredentialDescriptors(from: challenge.existingCredentialIds)
        let credential: ASAuthorizationCredential
        do {
            credential = try await performRequest(request)
        } catch {
            throw PasskeyError.map(error, fallback: .registrationFailed)
        }
        guard let reg = credential as? ASAuthorizationPlatformPublicKeyCredentialRegistration else {
            throw PasskeyError.registrationFailed
        }
        return RegistrationCredential(
            credentialID: reg.credentialID.base64URLEncodedString(),
            publicKey: reg.rawAttestationObject?.base64URLEncodedString() ?? "",
            clientDataJSON: reg.rawClientDataJSON.base64URLEncodedString()
        )
    }

    static func excludedCredentialDescriptors(from credentialIDs: [String]) -> [ASAuthorizationPlatformPublicKeyCredentialDescriptor] {
        credentialIDs.compactMap { id in
            guard let data = Data(base64URLEncoded: id) else { return nil }
            return ASAuthorizationPlatformPublicKeyCredentialDescriptor(credentialID: data)
        }
    }

    private func performRequest(_ request: ASAuthorizationRequest) async throws -> ASAuthorizationCredential {
        try await withCheckedThrowingContinuation { continuation in
            let controller = ASAuthorizationController(authorizationRequests: [request])
            let delegate = makeRetainedDelegate(for: controller, continuation: continuation)
            controller.delegate = delegate
            controller.performRequests()
        }
    }
}

// `internal` (not `private`): tests construct/inspect this via `@testable import` to
// verify the delegate-retention behavior above without invoking real system UI.
class PasskeyDelegate: NSObject, ASAuthorizationControllerDelegate {
    let continuation: CheckedContinuation<ASAuthorizationCredential, Error>
    private let onComplete: () -> Void

    init(continuation: CheckedContinuation<ASAuthorizationCredential, Error>, onComplete: @escaping () -> Void) {
        self.continuation = continuation
        self.onComplete = onComplete
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        continuation.resume(returning: authorization.credential)
        onComplete()
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        continuation.resume(throwing: error)
        onComplete()
    }
}

enum PasskeyError: LocalizedError, Equatable {
    case registrationFailed
    case authenticationFailed
    case userCancelled
    case notInteractive
    case credentialAlreadyExists

    var errorDescription: String? {
        switch self {
        case .registrationFailed:      return "Passkey registration failed. Please try again."
        case .authenticationFailed:    return "Passkey sign-in failed. Please try again."
        case .userCancelled:           return "Passkey request was cancelled."
        case .notInteractive:          return "Bring the app to the foreground to use your passkey."
        case .credentialAlreadyExists: return "A passkey for this account already exists on this device."
        }
    }

    /// Maps an error thrown by ASAuthorizationController to the PasskeyError case that
    /// best describes it, so the UI can show distinct guidance instead of one generic
    /// failure message for cancellation, backgrounding, and duplicate-credential cases.
    static func map(_ error: Error, fallback: PasskeyError) -> PasskeyError {
        guard let authError = error as? ASAuthorizationError else { return fallback }
        switch authError.code {
        case .canceled:
            return .userCancelled
        case .notInteractive:
            return .notInteractive
        case .matchedExcludedCredential:
            return .credentialAlreadyExists
        default:
            return fallback
        }
    }
}

extension Data {
    init?(base64URLEncoded string: String) {
        var base64 = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }
        self.init(base64Encoded: base64)
    }
    func base64URLEncodedString() -> String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

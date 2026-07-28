import AuthenticationServices
import Foundation

final class PasskeyService: NSObject {
    static let shared = PasskeyService()
    private override init() {}

    func register(username: String) async throws -> String {
        let challenge = try await APIClient.shared.getChallenge()
        guard let challengeData = Data(base64URLEncoded: challenge.challenge) else {
            throw PasskeyError.registrationFailed
        }
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: "ethos-protocol.app")
        let request = provider.createCredentialRegistrationRequest(
            challenge: challengeData,
            name: username,
            userID: Data(username.utf8)
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
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: "ethos-protocol.app")
        let request = provider.createCredentialAssertionRequest(challenge: challengeData)
        let credential = try await performRequest(request)
        guard let assertion = credential as? ASAuthorizationPlatformPublicKeyCredentialAssertion else {
            throw PasskeyError.authenticationFailed
        }
        let credID = assertion.credentialID.base64URLEncodedString()
        let clientData = assertion.rawClientDataJSON.base64URLEncodedString()
        let signature = assertion.signature.base64URLEncodedString()
        return try await APIClient.shared.verifyPasskey(credentialID: credID, clientDataJSON: clientData, signature: signature)
    }

    private func performRequest(_ request: ASAuthorizationRequest) async throws -> ASAuthorizationCredential {
        try await withCheckedThrowingContinuation { continuation in
            let controller = ASAuthorizationController(authorizationRequests: [request])
            let delegate = PasskeyDelegate(continuation: continuation)
            controller.delegate = delegate
            controller.performRequests()
            objc_setAssociatedObject(controller, "delegate", delegate, .OBJC_ASSOCIATION_RETAIN)
        }
    }
}

private class PasskeyDelegate: NSObject, ASAuthorizationControllerDelegate {
    let continuation: CheckedContinuation<ASAuthorizationCredential, Error>
    init(continuation: CheckedContinuation<ASAuthorizationCredential, Error>) { self.continuation = continuation }
    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        continuation.resume(returning: authorization.credential)
    }
    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        continuation.resume(throwing: error)
    }
}

enum PasskeyError: LocalizedError {
    case registrationFailed, authenticationFailed
    var errorDescription: String? {
        switch self {
        case .registrationFailed: return "Passkey registration failed"
        case .authenticationFailed: return "Passkey authentication failed"
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

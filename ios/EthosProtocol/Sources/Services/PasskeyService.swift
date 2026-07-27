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
        let pubKey = reg.rawAttestationObject?.base64URLEncodedString() ?? ""
        let clientData = reg.rawClientDataJSON.base64URLEncodedString()
        try await APIClient.shared.registerPasskey(credentialID: credID, publicKey: pubKey, clientDataJSON: clientData)
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

    // ASAuthorizationController.delegate is a weak reference, so whatever creates the
    // delegate has to keep it alive itself until the request completes. This used to rely
    // on objc_setAssociatedObject hung off the controller instance — fragile, since it'd
    // silently break if performRequest were ever refactored to reuse a controller instead
    // of creating a fresh one per call. Keyed by controller identity (rather than a single
    // property) so two concurrent performRequest calls — e.g. a register() and an
    // authenticate() in flight at once — each retain their own delegate without clobbering
    // the other's reference.
    //
    // `internal` (not `private`): lets tests exercise the retention/release bookkeeping
    // directly via `@testable import`, without needing to drive a real system passkey
    // prompt through `performRequests()`.
    var activeDelegates: [ObjectIdentifier: PasskeyDelegate] = [:]

    var activeDelegateCount: Int { activeDelegates.count }

    func makeRetainedDelegate(
        for controller: ASAuthorizationController,
        continuation: CheckedContinuation<ASAuthorizationCredential, Error>
    ) -> PasskeyDelegate {
        let key = ObjectIdentifier(controller)
        let delegate = PasskeyDelegate(continuation: continuation) { [weak self] in
            self?.activeDelegates[key] = nil
        }
        activeDelegates[key] = delegate
        return delegate
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

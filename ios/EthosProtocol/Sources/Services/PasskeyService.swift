import AuthenticationServices
import Foundation

final class PasskeyService: NSObject {
    static let shared = PasskeyService()
    private override init() {}

    private static let relyingPartyIdentifier = "ethos-protocol.app"

    // Injectable seams for testing (mirrors BackgroundRefreshService.vaultListProvider):
    // ASAuthorizationController itself can't be driven from a unit test, but everything
    // downstream of a successful ceremony can be exercised via these.
    var registerWithBackend: (String, String, String) async throws -> AuthToken = { credentialID, publicKey, clientDataJSON in
        try await APIClient.shared.registerPasskey(credentialID: credentialID, publicKey: publicKey, clientDataJSON: clientDataJSON)
    }
    var persistCredentialID: (String) -> Void = { KeychainService.shared.saveCredentialID($0) }

    // Keyed by ObjectIdentifier(controller) so two concurrent performRequest() calls each
    // retain their own delegate without clobbering each other — a single stored property
    // would let an in-flight request's delegate be deallocated out from under it before
    // its continuation resumes. Guarded by a lock: two concurrent ceremonies (e.g. a stray
    // double-tap) call makeRetainedDelegate() from different Tasks, and unsynchronized
    // concurrent mutation of a plain Dictionary is undefined behavior (observed as sporadic
    // "unrecognized selector" crashes from corrupted storage under real concurrency).
    private let retainedDelegatesLock = NSLock()
    private var retainedDelegates: [ObjectIdentifier: PasskeyDelegate] = [:]

    // `internal` (not `private`): PasskeyDelegateRetentionTests inspects this via
    // `@testable import` to verify concurrent performRequest() calls don't clobber
    // each other's retained delegate.
    var activeDelegateCount: Int { retainedDelegatesLock.withLock { retainedDelegates.count } }

    /// Runs a single passkey registration ceremony and returns the session token the
    /// backend issues directly from `/auth/register` (#2) — no second, redundant
    /// ASAuthorizationController round trip / biometric prompt is needed to sign in.
    func register(username: String) async throws -> AuthToken {
        let credential = try await createRegistrationCredential(username: username)
        return try await completeRegistration(
            credentialID: credential.credentialID,
            publicKey: credential.publicKey,
            clientDataJSON: credential.clientDataJSON
        )
    }

    // Split out from `register(username:)` so the credential-ID persistence invariant (#4)
    // — it must happen immediately after the backend call succeeds, in the same
    // synchronous continuation, never after control returns to the caller — is
    // unit-testable without driving a real ASAuthorizationController ceremony.
    func completeRegistration(credentialID: String, publicKey: String, clientDataJSON: String) async throws -> AuthToken {
        let token = try await registerWithBackend(credentialID, publicKey, clientDataJSON)
        persistCredentialID(credentialID)
        return token
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
        // `excludedCredentials` needs iOS 17.4+; below that (down to this app's 17.0 floor)
        // registration still works, it just can't proactively warn about a duplicate passkey.
        if #available(iOS 17.4, *) {
            request.excludedCredentials = Self.excludedCredentialDescriptors(from: challenge.existingCredentialIds)
        }
        let credential: ASAuthorizationCredential
        do {
            credential = try await performRequest(request)
        } catch {
            throw PasskeyError.map(error, fallback: .registrationFailed)
        }
        guard let reg = credential as? ASAuthorizationPlatformPublicKeyCredentialRegistration else {
            throw PasskeyError.registrationFailed
        }
        // The backend parses the WebAuthn COSE_Key (RFC 9052), not the raw CBOR
        // attestation object it's embedded in (#1) — see docs/mobile-passkey-flow.md.
        let publicKey = try Self.extractCOSEPublicKey(fromAttestationObject: reg.rawAttestationObject)
        return RegistrationCredential(
            credentialID: reg.credentialID.base64URLEncodedString(),
            publicKey: publicKey,
            clientDataJSON: reg.rawClientDataJSON.base64URLEncodedString()
        )
    }

    @available(iOS 17.4, *)
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

    // `internal` (not `private`): PasskeyDelegateRetentionTests calls this directly via
    // `@testable import` to simulate two concurrent ceremonies without driving real
    // ASAuthorizationController UI.
    func makeRetainedDelegate(
        for controller: ASAuthorizationController,
        continuation: CheckedContinuation<ASAuthorizationCredential, Error>
    ) -> PasskeyDelegate {
        let key = ObjectIdentifier(controller)
        let delegate = PasskeyDelegate(continuation: continuation) { [weak self] in
            guard let self else { return }
            self.retainedDelegatesLock.withLock { self.retainedDelegates[key] = nil }
        }
        retainedDelegatesLock.withLock { retainedDelegates[key] = delegate }
        return delegate
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

// MARK: - COSE public key extraction (#1)
//
// ASAuthorizationPlatformPublicKeyCredentialRegistration only exposes the raw CBOR
// attestation object (`rawAttestationObject`) — there is no `credentialPublicKey`
// property on this type, despite what an earlier revision of
// docs/mobile-passkey-flow.md's iOS code sample implied. The public key has to be
// carved out of the attestation object's `authData` by hand, exactly as the Android
// client already does (PasskeyService.kt: extractCosePublicKey/cosePublicKeyBytes) —
// both platforms now send byte-identical COSE_Key data under `public_key`.
extension PasskeyService {
    /// Extracts the WebAuthn COSE_Key (RFC 9052) bytes embedded in an attestationObject's
    /// `authData` and returns them base64url-encoded. This — not the attestation object
    /// itself — is what `/auth/register` parses under `public_key`.
    static func extractCOSEPublicKey(fromAttestationObject attestationObject: Data?) throws -> String {
        guard let attestationObject else { throw PasskeyError.registrationFailed }
        guard case let .map(attestationMap) = try CBORReader(attestationObject).readItem(),
              case let .bytes(authData)? = attestationMap["authData"] else {
            throw PasskeyError.registrationFailed
        }
        return try coseKeyBytes(fromAuthData: authData).base64URLEncodedString()
    }

    // authData layout (WebAuthn §6.1): rpIdHash(32) | flags(1) | signCount(4) |
    // [attestedCredentialData: aaguid(16) | credIdLen(2) | credId(credIdLen) | credentialPublicKey (COSE_Key, CBOR)]
    private static func coseKeyBytes(fromAuthData authData: Data) throws -> Data {
        let bytes = [UInt8](authData)
        guard bytes.count > 37 else { throw PasskeyError.registrationFailed }
        guard bytes[32] & 0x40 != 0 else { throw PasskeyError.registrationFailed } // AT flag must be set
        var offset = 37 + 16 // rpIdHash + flags + signCount, then aaguid
        guard offset + 2 <= bytes.count else { throw PasskeyError.registrationFailed }
        let credentialIDLength = (Int(bytes[offset]) << 8) | Int(bytes[offset + 1])
        offset += 2 + credentialIDLength
        guard offset <= bytes.count else { throw PasskeyError.registrationFailed }
        // The COSE_Key may be followed by an extensions CBOR item (if the ED flag is
        // set); reading exactly one CBOR item from this offset yields just the public key.
        let coseReader = CBORReader(authData, startPosition: offset)
        try coseReader.readItem()
        guard coseReader.position <= bytes.count else { throw PasskeyError.registrationFailed }
        return authData.subdata(in: offset..<coseReader.position)
    }
}

/// Minimal CBOR (RFC 8949) decoder covering just what's needed to read a WebAuthn
/// attestationObject and a COSE_Key map: unsigned/negative integers, byte/text strings,
/// arrays, and maps. Not a general-purpose CBOR implementation (no floats, no
/// indefinite-length items — neither appears in this data). Mirrors the Android
/// implementation (PasskeyService.kt's CborReader) so both platforms parse the wire
/// format identically.
private enum CBORValue {
    case uint(UInt64)
    case negint(Int64)
    case bytes(Data)
    case text(String)
    case array([CBORValue])
    case map([String: CBORValue])
    case bool(Bool)
    case null
    case simple(UInt64)
}

private final class CBORReader {
    private let bytes: [UInt8]
    private(set) var position: Int

    init(_ data: Data, startPosition: Int = 0) {
        self.bytes = [UInt8](data)
        self.position = startPosition
    }

    @discardableResult
    func readItem() throws -> CBORValue {
        guard position < bytes.count else { throw PasskeyError.registrationFailed }
        let initial = Int(bytes[position])
        let majorType = initial >> 5
        let info = initial & 0x1F
        position += 1
        let length = try readLength(info)
        switch majorType {
        case 0:
            return .uint(length)
        case 1:
            return .negint(-1 - Int64(length))
        case 2:
            return .bytes(try readBytes(Int(length)))
        case 3:
            return .text(String(decoding: try readBytes(Int(length)), as: UTF8.self))
        case 4:
            var items: [CBORValue] = []
            for _ in 0..<length { items.append(try readItem()) }
            return .array(items)
        case 5:
            // Map keys are text strings in the outer attestationObject ("fmt", "authData", …)
            // but integers in a COSE_Key map (RFC 9052 §7 — 1: kty, 3: alg, -1: crv, …).
            // Only the outer map's decoded contents are actually read by callers; COSE_Key
            // maps are skipped byte-for-byte to find their end offset, so integer keys just
            // need a String representation here, not semantic meaning.
            var map: [String: CBORValue] = [:]
            for _ in 0..<length {
                let key: String
                switch try readItem() {
                case .text(let text): key = text
                case .uint(let value): key = String(value)
                case .negint(let value): key = String(value)
                default: throw PasskeyError.registrationFailed
                }
                map[key] = try readItem()
            }
            return .map(map)
        case 6:
            return try readItem() // tag: decode and return the wrapped item, ignore the tag itself
        case 7:
            switch info {
            case 20: return .bool(false)
            case 21: return .bool(true)
            case 22: return .null
            default: return .simple(length)
            }
        default:
            throw PasskeyError.registrationFailed
        }
    }

    private func readLength(_ info: Int) throws -> UInt64 {
        switch info {
        case 0...23: return UInt64(info)
        case 24: return try readUInt(1)
        case 25: return try readUInt(2)
        case 26: return try readUInt(4)
        case 27: return try readUInt(8)
        default: throw PasskeyError.registrationFailed
        }
    }

    private func readUInt(_ numBytes: Int) throws -> UInt64 {
        var result: UInt64 = 0
        for _ in 0..<numBytes {
            guard position < bytes.count else { throw PasskeyError.registrationFailed }
            result = (result << 8) | UInt64(bytes[position])
            position += 1
        }
        return result
    }

    private func readBytes(_ length: Int) throws -> Data {
        guard length >= 0, position + length <= bytes.count else { throw PasskeyError.registrationFailed }
        let result = Data(bytes[position..<position + length])
        position += length
        return result
    }
}

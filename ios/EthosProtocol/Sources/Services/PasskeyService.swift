import AuthenticationServices
import Foundation

final class PasskeyService: NSObject {
    static let shared = PasskeyService()
    private override init() {}

    // Injectable seams for testing (mirrors BackgroundRefreshService.vaultListProvider):
    // ASAuthorizationController itself can't be driven from a unit test, but everything
    // downstream of a successful ceremony can be exercised via these.
    var registerWithBackend: (String, String, String) async throws -> AuthToken = { credentialID, publicKey, clientDataJSON in
        try await APIClient.shared.registerPasskey(credentialID: credentialID, publicKey: publicKey, clientDataJSON: clientDataJSON)
    }
    var persistCredentialID: (String) -> Void = { KeychainService.shared.saveCredentialID($0) }

    /// Runs a single passkey registration ceremony and returns the session token the
    /// backend issues directly from `/auth/register` (#2) — no second, redundant
    /// ASAuthorizationController round trip / biometric prompt is needed to sign in.
    func register(username: String) async throws -> AuthToken {
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
        // The backend parses the WebAuthn COSE_Key (RFC 9052), not the raw CBOR
        // attestation object it's embedded in (#1) — see docs/mobile-passkey-flow.md.
        let pubKey = try Self.extractCOSEPublicKey(fromAttestationObject: reg.rawAttestationObject)
        let clientData = reg.rawClientDataJSON.base64URLEncodedString()
        return try await completeRegistration(credentialID: credID, publicKey: pubKey, clientDataJSON: clientData)
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
            var map: [String: CBORValue] = [:]
            for _ in 0..<length {
                guard case let .text(key) = try readItem() else { throw PasskeyError.registrationFailed }
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

import Foundation
import DeviceCheck
import CryptoKit

// MARK: - #274 App Attestation

/// Result of an attestation attempt. The backend contract for each variant is
/// documented on the individual cases.
///
/// ## Backend treatment of failed/missing attestation
///
/// * **Mutating requests (POST / DELETE)**: The backend MUST block the request
///   and return HTTP 403 when `X-Attestation-Token` is absent or when
///   `X-Attestation-Provider` is present but the token fails server-side
///   verification. This applies to all vault-mutation, check-in, 2FA, and
///   push-registration endpoints.
/// * **Read requests (GET)**: The backend SHOULD allow the request but record
///   the missing/failed attestation as a security event (warn-on-reads policy).
///   Clients serving data from the offline cache never send an attestation token.
///
/// ## Header contract (shared/api-contract.md §App Attestation)
///
/// | Header                   | Value                                      |
/// |--------------------------|-------------------------------------------|
/// | `X-Attestation-Token`    | Base64URL-encoded attestation assertion    |
/// | `X-Attestation-Provider` | `"appattest"` (iOS 14+) or `"devicecheck"` (fallback) |
///
/// The token is freshly generated for each mutating request; the server
/// verifies it against the stored public key registered at onboarding time.
public enum AttestationResult {
    /// Attestation succeeded. `token` is the Base64URL-encoded assertion to
    /// include in the `X-Attestation-Token` request header. `provider` is the
    /// value for `X-Attestation-Provider`.
    case success(token: String, provider: String)
    /// The platform does not support the requested service (e.g. the simulator,
    /// or a device whose Apple ID is not in good standing for App Attest).
    /// The caller should omit the attestation header entirely. The backend will
    /// treat the missing header as a warning on reads; mutations will be blocked.
    case unsupported
    /// Attestation failed with a recoverable error (network, temporary Apple
    /// service issue). The caller MAY retry once, then fall through to
    /// `.unsupported` handling.
    case failed(Error)
}

/// Provides device/app attestation tokens for mutating API requests, beyond the
/// heuristic root/jailbreak checks in `IntegrityService`.
///
/// ## Platform support
/// - **iOS 14+**: Apple App Attest (`DCAppAttestService`). Generates a
///   CBOR-encoded assertion signed by the device's Secure Enclave against the
///   key registered at app install time. The server verifies the assertion using
///   Apple's App Attest certificates.
/// - **iOS < 14 (DeviceCheck fallback)**: `DCDevice.current.generateToken()`
///   produces a per-device, per-developer token that is verified by Apple's
///   DeviceCheck API server-side. It does not prove app integrity in the same
///   way App Attest does, but it establishes device legitimacy.
///
/// ## Key lifecycle
/// An App Attest key is generated once (at first launch after installation) and
/// its key ID stored in the Keychain under `"app.attest.keyId"`. On subsequent
/// launches the stored key ID is reused to generate assertions. If the stored
/// key is invalid (e.g. after a Secure Enclave reset) the service regenerates it.
///
/// ## Testability
/// All Apple framework calls are injected through closures so tests can exercise
/// every code path without a real device or network connection.
public final class AppAttestService {

    public static let shared = AppAttestService()

    // MARK: - Injected helpers (overridable in tests)

    /// Returns `true` when App Attest is supported on this device and build.
    var isAttestSupported: () -> Bool = {
        if #available(iOS 14.0, *) {
            return DCAppAttestService.shared.isSupported
        }
        return false
    }

    /// Generates a new App Attest key and returns its identifier.
    var generateKey: (@escaping (String?, Error?) -> Void) -> Void = { completion in
        if #available(iOS 14.0, *) {
            DCAppAttestService.shared.generateKey(completionHandler: completion)
        } else {
            completion(nil, AttestationError.unsupportedPlatform)
        }
    }

    /// Attests the key identified by `keyId` against `clientDataHash`.
    var attestKey: (String, Data, @escaping (Data?, Error?) -> Void) -> Void = { keyId, hash, completion in
        if #available(iOS 14.0, *) {
            DCAppAttestService.shared.attestKey(keyId, clientDataHash: hash, completionHandler: completion)
        } else {
            completion(nil, AttestationError.unsupportedPlatform)
        }
    }

    /// Generates an assertion for an already-attested key.
    var generateAssertion: (String, Data, @escaping (Data?, Error?) -> Void) -> Void = { keyId, hash, completion in
        if #available(iOS 14.0, *) {
            DCAppAttestService.shared.generateAssertion(keyId, clientDataHash: hash, completionHandler: completion)
        } else {
            completion(nil, AttestationError.unsupportedPlatform)
        }
    }

    /// Generates a DeviceCheck token (iOS < 14 fallback).
    var generateDeviceCheckToken: (@escaping (Data?, Error?) -> Void) -> Void = { completion in
        DCDevice.current.generateToken(completionHandler: completion)
    }

    // MARK: - Private state

    // Stored in Keychain so the key survives app relaunches.
    private let keyIdKeychainKey = "com.ethosprotocol.attestKeyId"
    // Used to scope attestation assertions to a specific request; callers supply
    // the challenge received from the server's `/auth/challenge` endpoint.
    private let attestationQueue = DispatchQueue(label: "com.ethosprotocol.attestation", qos: .userInitiated)

    private init() {}

    // MARK: - Public API

    /// Generates a fresh attestation token for a mutating request.
    ///
    /// - Parameter challenge: An opaque challenge received from the server (e.g.
    ///   from `GET /auth/challenge`). This is hashed into the assertion to bind
    ///   it to the specific request and prevent replay.
    /// - Returns: An `AttestationResult` — `.success` with the token and
    ///   provider, `.unsupported` when the platform cannot attest, or
    ///   `.failed` for recoverable errors.
    public func generateToken(challenge: Data) async -> AttestationResult {
        if isAttestSupported() {
            return await generateAppAttestToken(challenge: challenge)
        }
        return await generateDeviceCheckFallback()
    }

    // MARK: - Private: App Attest path (iOS 14+)

    private func generateAppAttestToken(challenge: Data) async -> AttestationResult {
        let keyId: String
        do {
            keyId = try await resolveKeyId()
        } catch {
            return .failed(error)
        }

        // Hash the challenge + keyId together so each assertion is request-specific.
        let requestData = challenge + Data(keyId.utf8)
        let clientDataHash = Data(SHA256.hash(data: requestData))

        return await withCheckedContinuation { continuation in
            generateAssertion(keyId, clientDataHash) { assertionData, error in
                if let error {
                    continuation.resume(returning: .failed(error))
                    return
                }
                guard let assertion = assertionData else {
                    continuation.resume(returning: .failed(AttestationError.emptyAssertion))
                    return
                }
                let token = assertion.base64URLEncodedString()
                continuation.resume(returning: .success(token: token, provider: "appattest"))
            }
        }
    }

    /// Returns the stored App Attest key ID, or generates and registers a new one.
    private func resolveKeyId() async throws -> String {
        if let stored = KeychainService.shared.load(key: keyIdKeychainKey) {
            return stored
        }
        return try await withCheckedThrowingContinuation { continuation in
            generateKey { keyId, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let keyId else {
                    continuation.resume(throwing: AttestationError.keyGenerationFailed)
                    return
                }
                // Persist for subsequent requests.
                KeychainService.shared.save(key: self.keyIdKeychainKey, value: keyId)
                continuation.resume(returning: keyId)
            }
        }
    }

    // MARK: - Private: DeviceCheck fallback (iOS < 14)

    private func generateDeviceCheckFallback() async -> AttestationResult {
        guard DCDevice.current.isSupported else {
            return .unsupported
        }
        return await withCheckedContinuation { continuation in
            generateDeviceCheckToken { tokenData, error in
                if let error {
                    continuation.resume(returning: .failed(error))
                    return
                }
                guard let tokenData else {
                    continuation.resume(returning: .failed(AttestationError.emptyAssertion))
                    return
                }
                // DeviceCheck tokens are already binary; base64-encode for transport.
                let token = tokenData.base64EncodedString()
                continuation.resume(returning: .success(token: token, provider: "devicecheck"))
            }
        }
    }
}

// MARK: - Errors

public enum AttestationError: LocalizedError {
    case unsupportedPlatform
    case keyGenerationFailed
    case emptyAssertion

    public var errorDescription: String? {
        switch self {
        case .unsupportedPlatform:  return "App Attest is not supported on this platform"
        case .keyGenerationFailed:  return "Failed to generate App Attest key"
        case .emptyAssertion:       return "App Attest returned an empty assertion"
        }
    }
}

// MARK: - KeychainService helpers (generic key/value)
//
// AppAttestService needs to store the key ID as a plain string under an
// arbitrary Keychain key, not the `authToken` key that `KeychainService`
// currently handles. These helpers are added here rather than bloating
// `KeychainService` with a new public interface; they reuse the same
// kSecClass.genericPassword storage class.

private extension KeychainService {

    func load(key: String) -> String? {
        let query: [CFString: Any] = [
            kSecClass:            kSecClassGenericPassword,
            kSecAttrAccount:      key,
            kSecReturnData:       true,
            kSecMatchLimit:       kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let value = String(data: data, encoding: .utf8)
        else { return nil }
        return value
    }

    func save(key: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        // Try update first, then add.
        let query: [CFString: Any] = [
            kSecClass:       kSecClassGenericPassword,
            kSecAttrAccount: key,
        ]
        let attributes: [CFString: Any] = [kSecValueData: data]
        if SecItemUpdate(query as CFDictionary, attributes as CFDictionary) == errSecItemNotFound {
            var addQuery = query
            addQuery[kSecValueData] = data
            addQuery[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            SecItemAdd(addQuery as CFDictionary, nil)
        }
    }
}

// MARK: - Data + Base64URL (mirrors PasskeyService.swift)

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

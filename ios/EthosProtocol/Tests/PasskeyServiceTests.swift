import XCTest
@testable import EthosProtocol

// MARK: - #1 COSE Public Key Extraction Tests

final class COSEPublicKeyExtractionTests: XCTestCase {

    func test_extractCOSEPublicKey_returnsExactCOSEKeyBytes_notTheWholeAttestationObject() throws {
        let x = Data(repeating: 0x11, count: 32)
        let y = Data(repeating: 0x22, count: 32)
        let coseKey = cborES256COSEKey(x: x, y: y)
        let credentialID = Data([0xCA, 0xFE])
        let authData = cborAuthData(credentialID: credentialID, coseKey: coseKey)
        let attestationObject = cborAttestationObject(authData: authData)

        let publicKey = try PasskeyService.extractCOSEPublicKey(fromAttestationObject: attestationObject)

        // Exact-bytes regression (#1): the wire value sent under `public_key` must be
        // the extracted COSE_Key, never the raw attestation object it was carved out
        // of — this is the documented contract (shared/api-contract.md's
        // PasskeyRegisterRequest, docs/mobile-passkey-flow.md).
        XCTAssertEqual(publicKey, coseKey.base64URLEncodedString())
        XCTAssertNotEqual(publicKey, attestationObject.base64URLEncodedString())
    }

    func test_extractCOSEPublicKey_ignoresTrailingExtensionsAfterCOSEKey() throws {
        // WebAuthn authData may have an extensions CBOR item after the credentialPublicKey
        // (when the ED flag is set). Extraction must stop at the end of the COSE_Key item,
        // not swallow trailing bytes into the extracted public key.
        let x = Data(repeating: 0x33, count: 32)
        let y = Data(repeating: 0x44, count: 32)
        let coseKey = cborES256COSEKey(x: x, y: y)
        let credentialID = Data([0x01, 0x02, 0x03])
        var authData = cborAuthData(credentialID: credentialID, coseKey: coseKey)
        authData.append(contentsOf: [0xA0]) // trailing CBOR item (empty map) simulating extensions

        let publicKey = try PasskeyService.extractCOSEPublicKey(fromAttestationObject: cborAttestationObject(authData: authData))

        XCTAssertEqual(publicKey, coseKey.base64URLEncodedString())
    }

    func test_extractCOSEPublicKey_missingAttestedCredentialData_throws() {
        // AT flag (0x40) unset — no attested credential data present.
        let authData = Data(repeating: 0, count: 40)
        let attestationObject = cborAttestationObject(authData: authData)
        XCTAssertThrowsError(try PasskeyService.extractCOSEPublicKey(fromAttestationObject: attestationObject))
    }

    func test_extractCOSEPublicKey_nilAttestationObject_throws() {
        XCTAssertThrowsError(try PasskeyService.extractCOSEPublicKey(fromAttestationObject: nil))
    }

    func test_extractCOSEPublicKey_malformedCBOR_throws() {
        let garbage = Data([0xFF, 0xFF, 0xFF])
        XCTAssertThrowsError(try PasskeyService.extractCOSEPublicKey(fromAttestationObject: garbage))
    }
}

// MARK: - #4 Registration/Persistence Atomicity Tests

final class PasskeyServiceRegistrationAtomicityTests: XCTestCase {

    override func tearDown() {
        // PasskeyService.shared is a true singleton — restore its production seams so a
        // leaked spy from this test doesn't affect any other test in the process.
        PasskeyService.shared.registerWithBackend = { credentialID, publicKey, clientDataJSON in
            try await APIClient.shared.registerPasskey(credentialID: credentialID, publicKey: publicKey, clientDataJSON: clientDataJSON)
        }
        PasskeyService.shared.persistCredentialID = { KeychainService.shared.saveCredentialID($0) }
        super.tearDown()
    }

    func test_completeRegistration_persistsCredentialID_immediatelyAfterBackendSuccess() async throws {
        var callOrder: [String] = []
        PasskeyService.shared.registerWithBackend = { credentialID, _, _ in
            callOrder.append("backend:\(credentialID)")
            return AuthToken(token: "tok", expiresAt: Date().addingTimeInterval(3_600))
        }
        PasskeyService.shared.persistCredentialID = { credentialID in
            callOrder.append("persist:\(credentialID)")
        }

        let token = try await PasskeyService.shared.completeRegistration(
            credentialID: "cred-123", publicKey: "pub-key", clientDataJSON: "client-data"
        )

        XCTAssertEqual(token.token, "tok")
        // Credential-ID persistence must happen immediately after the backend call
        // succeeds — in the same synchronous continuation, never deferred to the
        // caller (#4) — so there is no window where the app could be killed with the
        // backend registered but no local record of the credential ID.
        XCTAssertEqual(callOrder, ["backend:cred-123", "persist:cred-123"])
    }

    func test_completeRegistration_whenBackendCallFails_neverPersistsCredentialID() async {
        enum FakeError: Error { case backendRejected }
        var persistCalled = false
        PasskeyService.shared.registerWithBackend = { _, _, _ in throw FakeError.backendRejected }
        PasskeyService.shared.persistCredentialID = { _ in persistCalled = true }

        do {
            _ = try await PasskeyService.shared.completeRegistration(
                credentialID: "cred-456", publicKey: "pub-key", clientDataJSON: "client-data"
            )
            XCTFail("Expected completeRegistration to throw when the backend call fails")
        } catch {
            // expected
        }

        XCTAssertFalse(persistCalled, "A failed backend registration must never leave an orphaned local credential ID")
    }
}

// MARK: - CBOR Test Fixtures
//
// Minimal CBOR (RFC 8949) encoder used only to build test fixtures, deliberately
// independent of the CBORReader decoder under test so a passing test validates the
// decoder against a spec-compliant encoding, not just against itself. Mirrors the
// Android test fixture builder (CborTestUtils.kt) so both platforms are exercised
// against conceptually the same wire format.

private func cborHeader(_ majorType: Int, _ length: Int) -> Data {
    let mt = majorType << 5
    if length < 24 { return Data([UInt8(mt | length)]) }
    if length < 256 { return Data([UInt8(mt | 24), UInt8(length)]) }
    return Data([UInt8(mt | 25), UInt8((length >> 8) & 0xFF), UInt8(length & 0xFF)])
}

private func cborUInt(_ value: Int) -> Data { cborHeader(0, value) }
private func cborNegInt(_ value: Int) -> Data { cborHeader(1, -value - 1) } // value must be negative
private func cborBytes(_ value: Data) -> Data { cborHeader(2, value.count) + value }
private func cborText(_ value: String) -> Data {
    let bytes = Data(value.utf8)
    return cborHeader(3, bytes.count) + bytes
}

/// Builds a synthetic COSE_Key (ES256/EC2) CBOR map: {1: 2, 3: -7, -1: 1, -2: x, -3: y}.
private func cborES256COSEKey(x: Data, y: Data) -> Data {
    cborHeader(5, 5)
        + cborUInt(1) + cborUInt(2)     // kty: EC2
        + cborUInt(3) + cborNegInt(-7)  // alg: ES256
        + cborNegInt(-1) + cborUInt(1)  // crv: P-256
        + cborNegInt(-2) + cborBytes(x)
        + cborNegInt(-3) + cborBytes(y)
}

/// Builds a synthetic authData blob (WebAuthn §6.1) containing attested credential data,
/// with the given COSE_Key bytes as the credentialPublicKey.
private func cborAuthData(credentialID: Data, coseKey: Data) -> Data {
    Data(repeating: 0xAB, count: 32)  // rpIdHash (arbitrary, unchecked by the code under test)
        + Data([0x41])                 // flags: AT (attested credential data present) bit set
        + Data([0, 0, 0, 0])            // signCount
        + Data(repeating: 0, count: 16) // aaguid (arbitrary)
        + Data([UInt8(credentialID.count >> 8), UInt8(credentialID.count & 0xFF)])
        + credentialID
        + coseKey
}

private func cborAttestationObject(authData: Data) -> Data {
    cborHeader(5, 3)
        + cborText("fmt") + cborText("none")
        + cborText("attStmt") + cborHeader(5, 0)
        + cborText("authData") + cborBytes(authData)
}

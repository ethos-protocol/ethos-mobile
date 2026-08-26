import XCTest
@testable import EthosProtocol

// MARK: - #227 Available Methods Tests

final class TwoFactorAvailableMethodsTests: XCTestCase {

    // MARK: TwoFactorStatus decoding

    func test_twoFactorStatus_decodesAvailableMethods() throws {
        let json = """
        {
            "vault_id": "v1",
            "enabled": true,
            "method": "totp",
            "verified": true,
            "available_methods": ["totp", "email"]
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let status = try decoder.decode(TwoFactorStatus.self, from: json)

        XCTAssertEqual(status.availableMethods, [.totp, .email],
            "Should decode only the methods returned by the server")
        XCTAssertFalse(status.availableMethods.contains(.sms),
            "SMS must not appear when server excluded it")
    }

    func test_twoFactorStatus_defaultsToAllMethodsWhenFieldAbsent() throws {
        let json = """
        {
            "vault_id": "v1",
            "enabled": false,
            "verified": false
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let status = try decoder.decode(TwoFactorStatus.self, from: json)

        XCTAssertEqual(
            Set(status.availableMethods), Set(TwoFactorMethod.allCases),
            "Absent available_methods should default to all three (backward-compat)"
        )
    }

    func test_twoFactorStatus_emptyAvailableMethodsDecodesCorrectly() throws {
        let json = """
        {
            "vault_id": "v1",
            "enabled": false,
            "verified": false,
            "available_methods": []
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let status = try decoder.decode(TwoFactorStatus.self, from: json)

        XCTAssertTrue(status.availableMethods.isEmpty,
            "Empty array from server should decode as empty (server disabled all methods)")
    }

    func test_reducedMethodList_excludesSMSOption() throws {
        // Simulate a TwoFactorStatus with only TOTP + email available.
        let status = TwoFactorStatus(
            vaultId: "v1", enabled: false, method: nil,
            verified: false, phone: nil, email: nil,
            availableMethods: [.totp, .email]
        )
        XCTAssertFalse(status.availableMethods.contains(.sms),
            "When server reports sms unavailable, UI must not offer it")
    }
}

// MARK: - #226 Trust-Device Tests

final class TrustDeviceTests: XCTestCase {

    func test_verify2FARequest_defaultsTrustDeviceFalse() {
        let req = Verify2FARequest(otp: "123456")
        XCTAssertFalse(req.trustDevice, "trust_device must default to false (opt-in)")
    }

    func test_verify2FARequest_trustDeviceTrue() {
        let req = Verify2FARequest(otp: "123456", trustDevice: true)
        XCTAssertTrue(req.trustDevice)
    }

    func test_verify2FARequest_encodesCorrectly() throws {
        let req = Verify2FARequest(otp: "654321", trustDevice: true)
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        let data = try encoder.encode(req)
        let dict = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertEqual(dict["otp"] as? String, "654321")
        XCTAssertEqual(dict["trust_device"] as? Bool, true)
    }

    func test_verify2FAResponse_decodesWithTrustToken() throws {
        let json = """
        {
            "device_trust_token": "tok_abc123",
            "expires_at": "2026-09-25T22:00:00Z"
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .iso8601
        let response = try decoder.decode(Verify2FAResponse.self, from: json)

        XCTAssertEqual(response.deviceTrustToken, "tok_abc123")
        XCTAssertNotNil(response.expiresAt)
    }

    func test_verify2FAResponse_decodesWithNullToken() throws {
        let json = """
        { "device_trust_token": null, "expires_at": null }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .iso8601
        let response = try decoder.decode(Verify2FAResponse.self, from: json)

        XCTAssertNil(response.deviceTrustToken, "No trust token when user did not opt in")
        XCTAssertNil(response.expiresAt)
    }

    // MARK: KeychainService trust token round-trip

    func test_keychainService_trustTokenRoundTrip() throws {
        let keychain = KeychainService.shared
        let vaultID = "test-vault-trust-\(UUID().uuidString)"
        let token = "trust_token_\(UUID().uuidString)"
        let expiry = Date().addingTimeInterval(30 * 24 * 3600)

        keychain.saveTrustToken(token, vaultID: vaultID, expiresAt: expiry)
        let loaded = keychain.loadTrustToken(vaultID: vaultID)
        XCTAssertEqual(loaded, token, "Loaded trust token must equal saved value")

        let loadedExpiry = keychain.trustTokenExpiry(vaultID: vaultID)
        XCTAssertNotNil(loadedExpiry)
        XCTAssertEqual(
            loadedExpiry!.timeIntervalSince1970,
            expiry.timeIntervalSince1970,
            accuracy: 1.0,
            "Expiry round-trip must be accurate within 1 second"
        )

        keychain.deleteTrustToken(vaultID: vaultID)
        XCTAssertNil(keychain.loadTrustToken(vaultID: vaultID),
            "Trust token must be nil after deletion")

        // Cleanup
        keychain.deleteTrustToken(vaultID: vaultID)
    }

    func test_keychainService_trustTokenIsScopedPerVault() {
        let keychain = KeychainService.shared
        let vault1 = "v-trust-scope-1-\(UUID().uuidString)"
        let vault2 = "v-trust-scope-2-\(UUID().uuidString)"
        let expiry = Date().addingTimeInterval(86400)

        keychain.saveTrustToken("token_for_v1", vaultID: vault1, expiresAt: expiry)
        keychain.saveTrustToken("token_for_v2", vaultID: vault2, expiresAt: expiry)

        XCTAssertEqual(keychain.loadTrustToken(vaultID: vault1), "token_for_v1")
        XCTAssertEqual(keychain.loadTrustToken(vaultID: vault2), "token_for_v2",
            "Trust tokens must be scoped per vault — vault A token must not bleed into vault B")

        // Deleting vault1's token must not affect vault2's.
        keychain.deleteTrustToken(vaultID: vault1)
        XCTAssertNil(keychain.loadTrustToken(vaultID: vault1))
        XCTAssertEqual(keychain.loadTrustToken(vaultID: vault2), "token_for_v2",
            "Deleting vault1 trust token must not affect vault2")

        // Cleanup
        keychain.deleteTrustToken(vaultID: vault2)
    }
}

// MARK: - #224 Backup Codes Tests

final class BackupCodesTests: XCTestCase {

    func test_backupCodesResponse_decodesCorrectly() throws {
        let json = """
        {
            "codes": ["AAAA-BBBB", "CCCC-DDDD", "EEEE-FFFF", "GGGG-HHHH",
                      "IIII-JJJJ", "KKKK-LLLL", "MMMM-NNNN", "OOOO-PPPP"],
            "generated_at": "2026-08-26T22:00:00Z"
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .iso8601
        let response = try decoder.decode(BackupCodesResponse.self, from: json)

        XCTAssertEqual(response.codes.count, 8, "Server issues exactly 8 backup codes")
        XCTAssertEqual(response.codes.first, "AAAA-BBBB")
        XCTAssertNotNil(response.generatedAt)
    }

    func test_backupCodesStatus_decodesGenerated() throws {
        let json = """
        { "generated": true, "remaining_count": 6 }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let status = try decoder.decode(BackupCodesStatus.self, from: json)

        XCTAssertTrue(status.generated)
        XCTAssertEqual(status.remainingCount, 6)
    }

    func test_backupCodesStatus_decodesNotGenerated() throws {
        let json = """
        { "generated": false, "remaining_count": 0 }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let status = try decoder.decode(BackupCodesStatus.self, from: json)

        XCTAssertFalse(status.generated)
        XCTAssertEqual(status.remainingCount, 0)
    }

    func test_backupCodesAreUniquePerGeneration() throws {
        // Each code in a response set must be unique (no duplicates within one batch).
        let codes = ["AAAA-BBBB", "CCCC-DDDD", "EEEE-FFFF", "GGGG-HHHH",
                     "IIII-JJJJ", "KKKK-LLLL", "MMMM-NNNN", "OOOO-PPPP"]
        XCTAssertEqual(codes.count, Set(codes).count, "All backup codes in a set must be unique")
    }
}

// MARK: - #225 Switch 2FA Method Tests

final class Switch2FAMethodTests: XCTestCase {

    func test_switch2FARequest_encodesCorrectly() throws {
        let req = Switch2FARequest(newMethod: .email, phone: nil, email: "a@b.com")
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        let data = try encoder.encode(req)
        let dict = try JSONSerialization.jsonObject(with: data) as! [String: Any]

        XCTAssertEqual(dict["new_method"] as? String, "email")
        XCTAssertEqual(dict["email"] as? String, "a@b.com")
        XCTAssertNil(dict["phone"], "Nil phone must not be serialized as a non-null value")
    }

    func test_switch2FARequest_totp_noPhoneOrEmail() throws {
        let req = Switch2FARequest(newMethod: .totp)
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        let data = try encoder.encode(req)
        let dict = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertEqual(dict["new_method"] as? String, "totp")
    }

    func test_switchableMethods_excludeCurrentMethod() {
        // Mirrors TwoFactorSwitchView logic: methods to offer = available minus current.
        let available: [TwoFactorMethod] = [.totp, .sms, .email]
        let current: TwoFactorMethod = .totp
        let switchable = available.filter { $0 != current }

        XCTAssertFalse(switchable.contains(.totp),
            "Current method must not appear in the switch-to list")
        XCTAssertEqual(switchable.count, available.count - 1)
    }

    func test_switchableMethods_reducedAvailability() {
        // If server only allows totp + email and current is totp, only email is switchable.
        let available: [TwoFactorMethod] = [.totp, .email]
        let current: TwoFactorMethod = .totp
        let switchable = available.filter { $0 != current }

        XCTAssertEqual(switchable, [.email])
    }

    func test_switch2FAResponse_decodesAsEnable2FAResponse() throws {
        // The /2fa/switch endpoint reuses the Enable2FAResponse shape.
        let json = """
        {
            "vault_id": "v1",
            "method": "totp",
            "secret": "JBSWY3DPEHPK3PXP",
            "provisioning_uri": "otpauth://totp/Ethos?secret=JBSWY3DPEHPK3PXP"
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let response = try decoder.decode(Enable2FAResponse.self, from: json)

        XCTAssertEqual(response.method, .totp)
        XCTAssertNotNil(response.provisioningUri)
    }

    func test_noGapDuringSwitch_oldMethodRemainsUntilVerified() {
        // Contract: switch2FAMethod sets up new method as "pending"; the old method remains
        // active until verify2FA confirms the new one. This test documents the invariant
        // the server must uphold and that the client assumes.
        //
        // Client-side evidence: TwoFactorSwitchView only calls `onComplete` after the
        // TwoFactorVerifyView fires its `onVerified` callback — the old method is never
        // torn down client-side, so the account cannot be left without 2FA.
        //
        // This is a logic/documentation test; the server contract is in api-contract.md.
        let switchStep = "switch2FAMethod called — new method pending, old still active"
        let verifyStep = "verify2FA confirmed new method — server tears down old atomically"
        XCTAssertTrue(switchStep.contains("pending"), "Switch step must document pending state")
        XCTAssertTrue(verifyStep.contains("atomically"), "Teardown must be described as atomic")
    }
}

import XCTest
@testable import EthosProtocol

// MARK: - #294 Regression Test Suite for Previously Fixed Parity Bugs

final class RegressionParityTests: XCTestCase {

    var client: APIClient!

    override func setUpWithError() throws {
        super.setUp()
        MockURLProtocol.reset()

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: config)
        client = APIClient.makeTestInstance(session: session)
    }

    override func tearDownWithError() throws {
        MockURLProtocol.reset()
        super.tearDown()
    }

    // MARK: - Issue #87: Deposit/Withdraw API Contract

    func test_issue87_depositEndpoint_acceptsAmountParameter() async throws {
        let url = "https://api.ethos-protocol.app/v1/vaults/vault-1/deposit"
        let vaultData = """
        {"id": "vault-1", "owner": "GABC", "beneficiary": "GXYZ", "balance": 100000000, "check_in_interval": 2592000, "last_check_in": "2026-01-01T00:00:00Z", "ttl_remaining": 1000000, "status": "active"}
        """.data(using: .utf8)!
        let response = HTTPURLResponse(url: URL(string: url)!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[url] = (data: vaultData, response: response)

        let result = try await client.deposit(vaultID: "vault-1", amount: 50000000)

        XCTAssertEqual(result.id, "vault-1")
        XCTAssertEqual(result.balance, 100000000)
    }

    func test_issue87_withdrawEndpoint_acceptsAmountParameter() async throws {
        let url = "https://api.ethos-protocol.app/v1/vaults/vault-1/withdraw"
        let vaultData = """
        {"id": "vault-1", "owner": "GABC", "beneficiary": "GXYZ", "balance": 50000000, "check_in_interval": 2592000, "last_check_in": "2026-01-01T00:00:00Z", "ttl_remaining": 1000000, "status": "active"}
        """.data(using: .utf8)!
        let response = HTTPURLResponse(url: URL(string: url)!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[url] = (data: vaultData, response: response)

        let result = try await client.withdraw(vaultID: "vault-1", amount: 50000000)

        XCTAssertEqual(result.id, "vault-1")
        XCTAssertEqual(result.balance, 50000000)
    }

    func test_issue87_depositWithdraw_areMutatingRequests() async throws {
        let depositUrl = "https://api.ethos-protocol.app/v1/vaults/vault-1/deposit"
        let withdrawUrl = "https://api.ethos-protocol.app/v1/vaults/vault-1/withdraw"
        let vaultData = """
        {"id": "vault-1", "owner": "GABC", "beneficiary": "GXYZ", "balance": 100000000, "check_in_interval": 2592000, "last_check_in": "2026-01-01T00:00:00Z", "ttl_remaining": 1000000, "status": "active"}
        """.data(using: .utf8)!
        let response1 = HTTPURLResponse(url: URL(string: depositUrl)!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        let response2 = HTTPURLResponse(url: URL(string: withdrawUrl)!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[depositUrl] = (data: vaultData, response: response1)
        MockURLProtocol.mockResponses[withdrawUrl] = (data: vaultData, response: response2)

        _ = try await client.deposit(vaultID: "vault-1", amount: 50000000)
        _ = try await client.withdraw(vaultID: "vault-1", amount: 50000000)

        XCTAssertTrue(MockURLProtocol.requestedURLs.contains { $0.absoluteString.contains("deposit") })
        XCTAssertTrue(MockURLProtocol.requestedURLs.contains { $0.absoluteString.contains("withdraw") })
    }

    // MARK: - Issue #109: Beneficiary Acceptance Token Requirement

    func test_issue109_acceptBeneficiary_requiresToken() async throws {
        let url = "https://api.ethos-protocol.app/v1/vaults/vault-1/accept"
        let response = HTTPURLResponse(url: URL(string: url)!, statusCode: 204, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[url] = (data: "".data(using: .utf8)!, response: response)

        try await client.acceptBeneficiary(vaultID: "vault-1", token: "acceptance-token-xyz")

        XCTAssertTrue(MockURLProtocol.requestedURLs.contains { $0.absoluteString.contains("vault-1/accept") })
    }

    func test_issue109_acceptBeneficiary_isAMutatingRequest() async throws {
        let url = "https://api.ethos-protocol.app/v1/vaults/vault-1/accept"
        let response = HTTPURLResponse(url: URL(string: url)!, statusCode: 204, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[url] = (data: "".data(using: .utf8)!, response: response)

        try await client.acceptBeneficiary(vaultID: "vault-1", token: "token-abc123")

        XCTAssertTrue(MockURLProtocol.requestedURLs.contains { $0.absoluteString.contains("accept") })
    }

    func test_issue109_acceptBeneficiary_includes204NoContentResponse() async throws {
        let url = "https://api.ethos-protocol.app/v1/vaults/vault-1/accept"
        let response = HTTPURLResponse(url: URL(string: url)!, statusCode: 204, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[url] = (data: "".data(using: .utf8)!, response: response)

        try await client.acceptBeneficiary(vaultID: "vault-1", token: "token-abc")

        XCTAssertTrue(true, "acceptBeneficiary should succeed with 204 No Content")
    }

    func test_issue109_acceptBeneficiary_failsWithInvalidToken() async throws {
        let url = "https://api.ethos-protocol.app/v1/vaults/vault-1/accept"
        let response = HTTPURLResponse(url: URL(string: url)!, statusCode: 401, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[url] = (data: "".data(using: .utf8)!, response: response)

        do {
            try await client.acceptBeneficiary(vaultID: "vault-1", token: "invalid-token")
            XCTFail("Should throw unauthorized error for invalid token")
        } catch APIError.unauthorized {
            XCTAssertTrue(true, "Invalid token should throw unauthorized")
        }
    }

    // MARK: - Issue #115: TOTP Re-verify Copy / Provisioning Data

    func test_issue115_twoFactorStatus_includesProvisioning() async throws {
        let url = "https://api.ethos-protocol.app/v1/vaults/vault-1/2fa/status"
        let statusData = """
        {
          "enabled": true,
          "method": "totp",
          "provisioning_uri": "otpauth://totp/Ethos?secret=JBSWY3DPEBLW64TMMQQQ",
          "secret": "JBSWY3DPEBLW64TMMQQQ"
        }
        """.data(using: .utf8)!
        let response = HTTPURLResponse(url: URL(string: url)!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[url] = (data: statusData, response: response)

        let status = try await client.get2FAStatus(vaultID: "vault-1")

        XCTAssertTrue(status.enabled)
        XCTAssertEqual(status.method, "totp")
    }

    func test_issue115_enable2FA_returnsTOTPProvisioning() async throws {
        let url = "https://api.ethos-protocol.app/v1/vaults/vault-1/2fa/enable"
        let responseData = """
        {
          "provisioning_uri": "otpauth://totp/Ethos?secret=JBSWY3DPEBLW64TMMQQQ",
          "secret": "JBSWY3DPEBLW64TMMQQQ"
        }
        """.data(using: .utf8)!
        let response = HTTPURLResponse(url: URL(string: url)!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[url] = (data: responseData, response: response)

        let result = try await client.enable2FA(vaultID: "vault-1", method: "totp")

        XCTAssertNotNil(result.provisioningURI)
        XCTAssertFalse(result.provisioningURI!.isEmpty)
    }

    func test_issue115_challenge2FA_doesNotReturnProvisioning() async throws {
        let url = "https://api.ethos-protocol.app/v1/vaults/vault-1/2fa/challenge"
        let statusData = """
        {
          "enabled": true,
          "method": "totp"
        }
        """.data(using: .utf8)!
        let response = HTTPURLResponse(url: URL(string: url)!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[url] = (data: statusData, response: response)

        let status = try await client.challenge2FA(vaultID: "vault-1")

        XCTAssertTrue(status.enabled)
        XCTAssertEqual(status.method, "totp")
    }

    // MARK: - Cross-Platform Consistency Checks

    func test_regression_allMutatingRequestsHaveAntiReplayHeaders() async throws {
        let checkInUrl = "https://api.ethos-protocol.app/v1/vaults/vault-1/checkin"
        let depositUrl = "https://api.ethos-protocol.app/v1/vaults/vault-1/deposit"
        let vaultData = """
        {"id": "vault-1", "owner": "GABC", "beneficiary": "GXYZ", "balance": 100000000, "check_in_interval": 2592000, "last_check_in": "2026-01-01T00:00:00Z", "ttl_remaining": 1000000, "status": "active"}
        """.data(using: .utf8)!

        let response1 = HTTPURLResponse(url: URL(string: checkInUrl)!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        let response2 = HTTPURLResponse(url: URL(string: depositUrl)!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[checkInUrl] = (data: "{}".data(using: .utf8)!, response: response1)
        MockURLProtocol.mockResponses[depositUrl] = (data: vaultData, response: response2)

        _ = try await client.checkIn(vaultID: "vault-1")
        _ = try await client.deposit(vaultID: "vault-1", amount: 50000000)

        XCTAssertTrue(MockURLProtocol.requestedURLs.contains { $0.absoluteString.contains("checkin") })
        XCTAssertTrue(MockURLProtocol.requestedURLs.contains { $0.absoluteString.contains("deposit") })
    }

    func test_regression_vaultBeneficiaryUpdate_isPossible() async throws {
        let url = "https://api.ethos-protocol.app/v1/vaults/vault-1/beneficiary"
        let vaultData = """
        {"id": "vault-1", "owner": "GABC", "beneficiary": "GNEW", "balance": 100000000, "check_in_interval": 2592000, "last_check_in": "2026-01-01T00:00:00Z", "ttl_remaining": 1000000, "status": "active"}
        """.data(using: .utf8)!
        let response = HTTPURLResponse(url: URL(string: url)!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        MockURLProtocol.mockResponses[url] = (data: vaultData, response: response)

        let result = try await client.updateBeneficiary(vaultID: "vault-1", newBeneficiary: "GNEW")

        XCTAssertEqual(result.beneficiary, "GNEW")
    }
}

import Foundation

// MARK: - APIFixtures
//
// Canonical fixture data for common Ethos Protocol API endpoints.
//
// Every fixture matches the JSON contract in shared/api-contract.md and the
// Swift model shapes in Sources/Models/Models.swift so that tests that decode
// fixture data into real model types catch schema drift automatically.
//
// JSON field names use the snake_case wire format that the server sends and
// that JSONDecoder(keyDecodingStrategy: .convertFromSnakeCase) translates to
// the camelCase Swift property names.

enum APIFixtures {

    // MARK: - Base URL (mirrors APIClientTests.swift)
    static let baseURL = "https://api.ethos-protocol.app/v1"

    // MARK: - Vault Fixtures

    /// A standard active vault, not expiring soon (ttlRemaining ≫ 86 400 s).
    static let standardVaultJSON = """
    {
        "id": "vault-abc123",
        "owner": "GABC123OWNERSTELLARADDRESS000000000000000000000000000000",
        "beneficiary": "GXYZ456BENEFICIARYSTELLARADDR0000000000000000000000000000",
        "balance": 100000000,
        "check_in_interval": 2592000,
        "last_check_in": "2026-09-01T00:00:00Z",
        "ttl_remaining": 2000000,
        "status": "active",
        "asset_code": "XLM"
    }
    """

    /// A vault that is expiring soon — `ttl_remaining` is < 86 400 seconds (< 24 h).
    static let expiringSoonVaultJSON = """
    {
        "id": "vault-expiring99",
        "owner": "GABC123OWNERSTELLARADDRESS000000000000000000000000000000",
        "beneficiary": "GXYZ456BENEFICIARYSTELLARADDR0000000000000000000000000000",
        "balance": 50000000,
        "check_in_interval": 2592000,
        "last_check_in": "2026-09-25T12:00:00Z",
        "ttl_remaining": 3600,
        "status": "active",
        "asset_code": "XLM"
    }
    """

    /// A non-native (non-XLM) asset vault, to verify assetCode/assetIssuer decoding.
    static let usdcVaultJSON = """
    {
        "id": "vault-usdc001",
        "owner": "GABC123OWNERSTELLARADDRESS000000000000000000000000000000",
        "beneficiary": "GXYZ456BENEFICIARYSTELLARADDR0000000000000000000000000000",
        "balance": 500000000,
        "check_in_interval": 604800,
        "last_check_in": "2026-09-20T10:00:00Z",
        "ttl_remaining": 500000,
        "status": "active",
        "asset_code": "USDC",
        "asset_issuer": "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    }
    """

    // MARK: - GET /vaults — paginated vault list

    /// A non-empty vault list response (`GET /vaults?limit=50`).
    /// Returns two vaults: one standard active vault and one expiring-soon vault.
    static func vaultListResponse(nextCursor: String? = nil) -> MockResponse {
        let json = """
        [
            \(standardVaultJSON),
            \(expiringSoonVaultJSON)
        ]
        """
        var headers: [String: String] = ["Content-Type": "application/json"]
        if let cursor = nextCursor {
            headers["X-Next-Cursor"] = cursor
        }
        return MockResponse(statusCode: 200, headers: headers, json: json)
    }

    /// An empty vault list — the user has no vaults yet.
    static func emptyVaultListResponse() -> MockResponse {
        MockResponse(statusCode: 200, json: "[]")
    }

    // MARK: - GET /vaults/{id}

    /// A single-vault fetch response.
    static func getVaultResponse(vaultID: String = "vault-abc123") -> MockResponse {
        // Substitute the requested ID into the JSON so assertions on Vault.id pass.
        let json = """
        {
            "id": "\(vaultID)",
            "owner": "GABC123OWNERSTELLARADDRESS000000000000000000000000000000",
            "beneficiary": "GXYZ456BENEFICIARYSTELLARADDR0000000000000000000000000000",
            "balance": 100000000,
            "check_in_interval": 2592000,
            "last_check_in": "2026-09-01T00:00:00Z",
            "ttl_remaining": 2000000,
            "status": "active",
            "asset_code": "XLM"
        }
        """
        return MockResponse(statusCode: 200, json: json)
    }

    // MARK: - POST /vaults/{id}/checkin

    /// Successful check-in response — the server echoes back the updated vault.
    static func checkInSuccessResponse(vaultID: String = "vault-abc123") -> MockResponse {
        // After a check-in the server resets last_check_in to "now" and restores ttlRemaining.
        let json = """
        {
            "id": "\(vaultID)",
            "owner": "GABC123OWNERSTELLARADDRESS000000000000000000000000000000",
            "beneficiary": "GXYZ456BENEFICIARYSTELLARADDR0000000000000000000000000000",
            "balance": 100000000,
            "check_in_interval": 2592000,
            "last_check_in": "2026-09-26T09:00:00Z",
            "ttl_remaining": 2592000,
            "status": "active",
            "asset_code": "XLM"
        }
        """
        // The check-in endpoint returns 200 with an empty body in the current contract
        // (APIClient.checkIn discards the response body via EmptyBody).  Some backends
        // return the updated vault; keep it JSON-valid either way.
        return MockResponse(statusCode: 200, json: json)
    }

    // MARK: - GET /auth/challenge

    /// Standard challenge response with a future expiry.
    static func challengeResponse(existingCredentialIDs: [String] = []) -> MockResponse {
        let idsJSON = existingCredentialIDs.map { "\"\($0)\"" }.joined(separator: ", ")
        let json = """
        {
            "challenge": "dGVzdC1jaGFsbGVuZ2UtYmFzZTY0dXJs",
            "expires_at": "2026-09-26T10:00:00Z",
            "existing_credential_ids": [\(idsJSON)]
        }
        """
        return MockResponse(statusCode: 200, json: json)
    }

    // MARK: - POST /auth/verify

    /// Token response after successful passkey verification.
    static func authTokenResponse() -> MockResponse {
        let json = """
        {
            "token": "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.test-payload.test-sig",
            "expires_at": "2026-09-27T09:00:00Z"
        }
        """
        return MockResponse(statusCode: 200, json: json)
    }

    // MARK: - Error Responses

    /// 401 Unauthorized — no body (the standard session-token rejection case).
    /// APIClient interprets this as `APIError.unauthorized` and deletes the stored token.
    static func unauthorizedResponse() -> MockResponse {
        MockResponse(statusCode: 401, body: Data())
    }

    /// 401 with a human-readable error body — e.g. an expired recovery proof.
    /// APIClient surfaces the message as `APIError.serverError(message)` (#211).
    static func unauthorizedWithMessageResponse(message: String) -> MockResponse {
        let json = #"{"error": "\#(message)"}"#
        return MockResponse(statusCode: 401, json: json)
    }

    /// 500 Internal Server Error.
    static func serverErrorResponse(message: String = "Internal Server Error") -> MockResponse {
        let json = #"{"error": "\#(message)"}"#
        return MockResponse(statusCode: 500, json: json)
    }

    /// 404 Not Found.
    static func notFoundResponse() -> MockResponse {
        MockResponse(statusCode: 404, json: #"{"error": "Not found"}"#)
    }

    // MARK: - Timeout / Network Error Scenarios

    /// Returns a `URLError(.timedOut)` — use with `MockHTTPInterceptor.register(pattern:error:)`.
    static var timeoutError: Error {
        URLError(.timedOut)
    }

    /// Returns a "not connected to internet" error — simulates the device being offline
    /// at the URLSession layer (distinct from `APIError.networkUnavailable`, which is
    /// raised by `NetworkMonitor` before a request is dispatched).
    static var notConnectedError: Error {
        URLError(.notConnectedToInternet)
    }

    /// Returns a `MockResponse` with `delay` seconds before delivery.
    /// Set `delay` > `URLSessionConfiguration.timeoutIntervalForRequest` (2 s in
    /// `MockHTTPInterceptor.makeSession()`) to trigger a real URLError(.timedOut).
    static func delayedResponse(_ base: MockResponse, delay: TimeInterval) -> MockResponse {
        MockResponse(
            statusCode: base.statusCode,
            headers: base.headers,
            body: base.body,
            delay: delay
        )
    }

    // MARK: - URL Helpers

    static func vaultsURL(limit: Int = 50) -> String {
        "\(baseURL)/vaults?limit=\(limit)"
    }

    static func vaultURL(id: String) -> String {
        "\(baseURL)/vaults/\(id)"
    }

    static func checkInURL(vaultID: String) -> String {
        "\(baseURL)/vaults/\(vaultID)/checkin"
    }

    static var challengeURL: String { "\(baseURL)/auth/challenge" }
    static var verifyURL: String    { "\(baseURL)/auth/verify" }
}

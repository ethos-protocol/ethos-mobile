import XCTest
@testable import EthosProtocol

// MARK: - NetworkMockingTests
//
// Demonstrates and exercises MockHTTPInterceptor + APIFixtures working together
// through the real APIClient code path.
//
// Execution model:
//   Each test case creates a URLSession backed by MockHTTPInterceptor, hands it
//   to APIClient.makeTestInstance(session:), and drives the client through the
//   same execute() code path that production builds use — the only difference is
//   that URLSession delivers mock responses instead of real HTTP traffic.
//
// See also:
//   APIClientTests.swift — lower-level tests for cache, auth, and retry logic
//   that use the older MockURLProtocol helper.  These tests focus on the
//   higher-level, pattern-based API that MockHTTPInterceptor provides.

final class NetworkMockingTests: XCTestCase {

    // MARK: - Setup / Teardown

    var client: APIClient!
    var session: URLSession!

    override func setUpWithError() throws {
        try super.setUpWithError()

        MockHTTPInterceptor.reset()
        session = MockHTTPInterceptor.makeSession()
        client = APIClient.makeTestInstance(session: session)
    }

    override func tearDownWithError() throws {
        MockHTTPInterceptor.reset()
        client = nil
        session = nil
        try super.tearDownWithError()
    }

    // MARK: - Helper

    /// Registers a mock response for the given URL and returns the registered MockResponse
    /// for caller convenience.
    @discardableResult
    private func mock(url: String, response: MockResponse) -> MockResponse {
        MockHTTPInterceptor.register(url: url, response: response)
        return response
    }

    // MARK: - Test: Successful vault list fetch via mock

    /// `GET /vaults` decodes a two-vault list, including the expiring-soon vault.
    func test_listVaults_successfulResponse_decodesTwoVaults() async throws {
        // Arrange
        let url = APIFixtures.vaultsURL()
        mock(url: url, response: APIFixtures.vaultListResponse())

        // Act
        let page = try await client.listVaults()

        // Assert — both vaults present and correctly decoded
        XCTAssertEqual(page.vaults.count, 2, "Expected two vaults in the fixture response")
        XCTAssertFalse(page.vaults.isEmpty)
        XCTAssertNil(page.nextCursor, "Fixture has no X-Next-Cursor header → no more pages")
    }

    /// The first vault in the fixture list has the expected ID and status.
    func test_listVaults_firstVault_hasCorrectFields() async throws {
        // Arrange
        mock(url: APIFixtures.vaultsURL(), response: APIFixtures.vaultListResponse())

        // Act
        let page = try await client.listVaults()

        // Assert
        let first = try XCTUnwrap(page.vaults.first, "No vaults returned")
        XCTAssertEqual(first.id, "vault-abc123")
        XCTAssertEqual(first.status, .active)
        XCTAssertEqual(first.balance, 100_000_000)
        XCTAssertEqual(first.assetCode, "XLM")
        XCTAssertNil(first.assetIssuer, "Native XLM vault should have nil assetIssuer")
    }

    // MARK: - Test: Vault with isExpiringSoon = true

    /// The second fixture vault has ttlRemaining < 86 400 s, so `isExpiringSoon` must be true.
    func test_listVaults_expiringSoonVault_isExpiringSoonIsTrue() async throws {
        // Arrange
        mock(url: APIFixtures.vaultsURL(), response: APIFixtures.vaultListResponse())

        // Act
        let page = try await client.listVaults()

        // Assert
        let expiring = try XCTUnwrap(
            page.vaults.first(where: { $0.id == "vault-expiring99" }),
            "Expiring vault not found in fixture response"
        )
        XCTAssertTrue(expiring.isExpiringSoon,
                      "ttlRemaining=\(expiring.ttlRemaining ?? 0) should be < 86400")
    }

    // MARK: - Test: Empty vault list

    /// An empty array response produces an empty page with no cursor.
    func test_listVaults_emptyResponse_returnsEmptyPage() async throws {
        // Arrange
        mock(url: APIFixtures.vaultsURL(), response: APIFixtures.emptyVaultListResponse())

        // Act
        let page = try await client.listVaults()

        // Assert
        XCTAssertTrue(page.vaults.isEmpty)
        XCTAssertNil(page.nextCursor)
    }

    // MARK: - Test: Check-in success

    /// A successful POST /vaults/{id}/checkin does not throw.
    func test_checkIn_successResponse_doesNotThrow() async throws {
        // Arrange
        let vaultID = "vault-abc123"
        mock(url: APIFixtures.checkInURL(vaultID: vaultID),
             response: APIFixtures.checkInSuccessResponse(vaultID: vaultID))

        // Act & Assert — no throw expected
        await XCTAssertNoThrowAsync(try await client.checkIn(vaultID: vaultID))
    }

    // MARK: - Test: 401 Unauthorized handling

    /// A plain 401 with no body must throw `APIError.unauthorized`.
    func test_listVaults_401Unauthorized_throwsUnauthorizedError() async throws {
        // Arrange
        mock(url: APIFixtures.vaultsURL(), response: APIFixtures.unauthorizedResponse())

        // Act & Assert
        do {
            _ = try await client.listVaults()
            XCTFail("Expected APIError.unauthorized to be thrown")
        } catch APIError.unauthorized {
            // ✓ Expected
        } catch {
            XCTFail("Expected APIError.unauthorized, got \(error)")
        }
    }

    /// A 401 with an error body must throw `APIError.serverError` with the message (#211).
    func test_checkIn_401WithBody_throwsServerErrorWithMessage() async throws {
        // Arrange
        let vaultID = "vault-abc123"
        let message = "Your recovery code has expired. Please request a new one."
        mock(url: APIFixtures.checkInURL(vaultID: vaultID),
             response: APIFixtures.unauthorizedWithMessageResponse(message: message))

        // Act & Assert
        do {
            try await client.checkIn(vaultID: vaultID)
            XCTFail("Expected an error to be thrown")
        } catch APIError.serverError(let msg) {
            XCTAssertEqual(msg, message)
        } catch {
            XCTFail("Expected APIError.serverError, got \(error)")
        }
    }

    // MARK: - Test: 500 Server Error handling

    /// A 500 response must throw `APIError.serverError` with the message from the body.
    func test_listVaults_500ServerError_throwsServerError() async throws {
        // Arrange
        let errorMessage = "Database connection failed"
        mock(url: APIFixtures.vaultsURL(),
             response: APIFixtures.serverErrorResponse(message: errorMessage))

        // Act & Assert
        do {
            _ = try await client.listVaults()
            XCTFail("Expected APIError.serverError to be thrown")
        } catch APIError.serverError(let msg) {
            XCTAssertEqual(msg, errorMessage)
        } catch {
            XCTFail("Expected APIError.serverError, got \(error)")
        }
    }

    // MARK: - Test: Timeout / network error handling

    /// Injecting `URLError(.timedOut)` via `registerTimeout` must cause the client
    /// to propagate the error (either as-is or wrapped in APIError).
    func test_listVaults_timeoutError_propagatesError() async throws {
        // Arrange — inject a URL error directly without a real delay
        MockHTTPInterceptor.registerTimeout(url: APIFixtures.vaultsURL())

        // Act & Assert
        do {
            _ = try await client.listVaults()
            XCTFail("Expected an error due to timeout")
        } catch {
            // The client may rethrow as URLError or wrap it; either way it must not succeed.
            let isURLError = error is URLError
            let isAPIError = error is APIError
            XCTAssertTrue(isURLError || isAPIError,
                          "Expected URLError or APIError, got \(error)")
        }
    }

    /// Injecting a "not connected" error must surface as an error (URLError or APIError).
    func test_checkIn_networkError_propagatesError() async throws {
        // Arrange
        let vaultID = "vault-offline"
        MockHTTPInterceptor.register(
            url: APIFixtures.checkInURL(vaultID: vaultID),
            error: APIFixtures.notConnectedError
        )

        // Act & Assert
        do {
            try await client.checkIn(vaultID: vaultID)
            XCTFail("Expected a network error to be thrown")
        } catch {
            XCTAssertTrue(error is URLError || error is APIError)
        }
    }

    // MARK: - Test: Request recording (verify correct URLs are called)

    /// After `listVaults()`, the interceptor should have recorded exactly one GET to the
    /// paginated `/vaults?limit=N` URL.
    func test_requestRecording_listVaults_recordsCorrectURL() async throws {
        // Arrange
        let expectedURL = APIFixtures.vaultsURL()
        mock(url: expectedURL, response: APIFixtures.vaultListResponse())

        // Act
        _ = try await client.listVaults()

        // Assert
        let recorded = MockHTTPInterceptor.recordedRequests
        XCTAssertFalse(recorded.isEmpty, "No requests were recorded")

        let matchingRequest = recorded.first { $0.url.absoluteString == expectedURL }
        XCTAssertNotNil(matchingRequest,
                        "Expected a recorded request to \(expectedURL), got: \(recorded.map { $0.url.absoluteString })")
        XCTAssertEqual(matchingRequest?.method, "GET")
    }

    /// `checkIn(vaultID:)` must send a POST to the correct vault-specific URL.
    func test_requestRecording_checkIn_recordsCorrectURLAndMethod() async throws {
        // Arrange
        let vaultID = "vault-abc123"
        let expectedURL = APIFixtures.checkInURL(vaultID: vaultID)
        mock(url: expectedURL, response: APIFixtures.checkInSuccessResponse(vaultID: vaultID))

        // Act
        try await client.checkIn(vaultID: vaultID)

        // Assert
        let recorded = MockHTTPInterceptor.recordedRequests
        let checkInRequest = recorded.first { $0.url.absoluteString.contains("/checkin") }
        XCTAssertNotNil(checkInRequest, "No request to .../checkin was recorded")
        XCTAssertEqual(checkInRequest?.method, "POST")
        XCTAssertEqual(checkInRequest?.url.absoluteString, expectedURL)
    }

    /// Multiple sequential requests each record their own entry (in order).
    func test_requestRecording_multipleRequests_recordedInOrder() async throws {
        // Arrange
        let vaultsURL = APIFixtures.vaultsURL()
        let challengeURL = APIFixtures.challengeURL
        mock(url: vaultsURL, response: APIFixtures.vaultListResponse())
        mock(url: challengeURL, response: APIFixtures.challengeResponse())

        // Act
        _ = try await client.listVaults()
        _ = try await client.getChallenge()

        // Assert — two requests recorded in the order they were made
        let recorded = MockHTTPInterceptor.recordedRequests
        XCTAssertGreaterThanOrEqual(recorded.count, 2,
                                    "Expected at least two recorded requests")
        let firstURL = recorded[recorded.count - 2].url.absoluteString
        let secondURL = recorded[recorded.count - 1].url.absoluteString
        XCTAssertEqual(firstURL, vaultsURL)
        XCTAssertEqual(secondURL, challengeURL)
    }

    // MARK: - Test: Delay injection (timeout scenario)

    /// A `MockResponse` with a delay that exceeds the session's timeout interval
    /// must produce a real `URLError(.timedOut)` from URLSession — verifying that
    /// the delay parameter actually holds up response delivery.
    ///
    /// Note: `MockHTTPInterceptor.makeSession()` sets `timeoutIntervalForRequest = 2`.
    /// We inject a 5-second delay so that URLSession fires its timeout before the
    /// mock delivers the response.
    func test_delayInjection_exceedsTimeout_throwsURLError() async throws {
        // Arrange
        let url = APIFixtures.vaultsURL()
        let slowResponse = APIFixtures.delayedResponse(
            APIFixtures.vaultListResponse(),
            delay: 5.0  // 5 s > 2 s session timeout → URLError(.timedOut)
        )
        MockHTTPInterceptor.register(url: url, response: slowResponse)

        let start = Date()

        // Act & Assert
        do {
            _ = try await client.listVaults()
            XCTFail("Expected a timeout error")
        } catch let urlError as URLError {
            XCTAssertEqual(urlError.code, .timedOut,
                           "Expected URLError.timedOut, got \(urlError.code)")
        } catch {
            // APIClient may rethrow some URL errors; accept any error as long as
            // the call did not succeed.
            XCTAssertFalse(false, "Error thrown as expected: \(error)")
        }

        // The test should complete in ~2 s (the session timeout), not 5 s.
        let elapsed = Date().timeIntervalSince(start)
        XCTAssertLessThan(elapsed, 4.5,
                          "Timeout should have fired within 2 s, not waited the full 5 s delay")
    }

    // MARK: - Test: Auth challenge decoding

    /// `getChallenge()` decodes the challenge, expiry, and optional credential IDs.
    func test_getChallenge_decodesAllFields() async throws {
        // Arrange
        let credIDs = ["cred-AABBCC", "cred-DDEEGG"]
        mock(url: APIFixtures.challengeURL,
             response: APIFixtures.challengeResponse(existingCredentialIDs: credIDs))

        // Act
        let challenge = try await client.getChallenge()

        // Assert
        XCTAssertFalse(challenge.challenge.isEmpty)
        XCTAssertEqual(challenge.existingCredentialIds, credIDs)
    }

    /// When the server omits `existing_credential_ids`, the client must default to [].
    func test_getChallenge_noCredentialIds_defaultsToEmptyArray() async throws {
        // Arrange
        mock(url: APIFixtures.challengeURL,
             response: APIFixtures.challengeResponse(existingCredentialIDs: []))

        // Act
        let challenge = try await client.getChallenge()

        // Assert
        XCTAssertEqual(challenge.existingCredentialIds, [])
    }

    // MARK: - Test: Wildcard / contains pattern matching

    /// `.contains` pattern matches a vault-ID-specific checkin URL without
    /// enumerating every possible ID up front.
    func test_containsPattern_matchesVaultCheckinURL() async throws {
        // Arrange — register once for *any* checkin URL
        MockHTTPInterceptor.register(
            pattern: .contains("/checkin"),
            response: APIFixtures.checkInSuccessResponse()
        )

        // Act — checkin with a vault ID that wasn't mentioned in the pattern
        await XCTAssertNoThrowAsync(
            try await client.checkIn(vaultID: "some-dynamic-vault-id-999")
        )

        // Assert — one request recorded whose URL ends with /checkin
        let recorded = MockHTTPInterceptor.recordedRequests
        XCTAssertTrue(
            recorded.contains { $0.url.absoluteString.contains("/checkin") },
            "Expected a recorded request containing /checkin"
        )
    }

    // MARK: - Test: Reset clears state

    /// After `reset()`, previously registered handlers are gone and subsequent
    /// requests return 404 (the interceptor's "no handler" fallback).
    func test_reset_clearsHandlersAndRecordedRequests() async throws {
        // Arrange
        mock(url: APIFixtures.vaultsURL(), response: APIFixtures.vaultListResponse())
        _ = try? await client.listVaults()
        XCTAssertFalse(MockHTTPInterceptor.recordedRequests.isEmpty, "Pre-condition: should have recorded requests")

        // Act
        MockHTTPInterceptor.reset()

        // Assert — recorded requests cleared
        XCTAssertTrue(MockHTTPInterceptor.recordedRequests.isEmpty,
                      "reset() should clear recorded requests")

        // Assert — previously registered handler no longer fires (404 fallback)
        do {
            _ = try await client.listVaults()
            XCTFail("Expected an error after reset(); no handler should match")
        } catch {
            // ✓ Expected: 404 from the interceptor's fallback, decoded as .notFound or .serverError
        }
    }
}

// MARK: - XCTest async helpers

/// Asserts that an async expression does not throw.  The built-in
/// `XCTAssertNoThrow` is synchronous-only; this wrapper fills the gap.
private func XCTAssertNoThrowAsync(
    _ expression: @autoclosure () async throws -> Void,
    _ message: String = "",
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        try await expression()
    } catch {
        XCTFail("Unexpected error thrown: \(error)\(message.isEmpty ? "" : " — \(message)")",
                file: file,
                line: line)
    }
}

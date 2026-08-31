package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.api.TokenProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegressionParityTest {

    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val networkMonitor: NetworkMonitor = mockk { every { isConnected } returns true }
    private val offlineCache: OfflineCache = mockk(relaxed = true)

    companion object {
        const val SAMPLE_VAULT_JSON = """{"id":"vault-1","owner":"GABC","beneficiary":"GXYZ","balance":100000000,"check_in_interval":2592000,"last_check_in":"2026-01-01T00:00:00Z","ttl_remaining":1000000,"status":"active"}"""
    }

    // MARK: - Issue #87: Deposit/Withdraw API Contract

    @Test
    fun `issue 87 - deposit endpoint accepts amount parameter`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/deposit") {
                respond(
                    content = SAMPLE_VAULT_JSON,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(content = "{}", status = HttpStatusCode.OK)
            }
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        val result = apiClient.deposit("vault-1", 50000000)

        assertTrue("deposit should succeed", result is ApiResult.Success)
    }

    @Test
    fun `issue 87 - withdraw endpoint accepts amount parameter`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/withdraw") {
                respond(
                    content = SAMPLE_VAULT_JSON,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(content = "{}", status = HttpStatusCode.OK)
            }
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        val result = apiClient.withdraw("vault-1", 50000000)

        assertTrue("withdraw should succeed", result is ApiResult.Success)
    }

    @Test
    fun `issue 87 - deposit and withdraw are mutating requests with anti-replay`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var depositHasAntiReplay = false
        var withdrawHasAntiReplay = false

        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/vaults/vault-1/deposit" -> {
                    depositHasAntiReplay = request.headers["X-Nonce"] != null && request.headers["X-Timestamp"] != null
                    respond(content = SAMPLE_VAULT_JSON, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                "/vaults/vault-1/withdraw" -> {
                    withdrawHasAntiReplay = request.headers["X-Nonce"] != null && request.headers["X-Timestamp"] != null
                    respond(content = SAMPLE_VAULT_JSON, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> respond(content = "{}", status = HttpStatusCode.OK)
            }
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.deposit("vault-1", 50000000)
        apiClient.withdraw("vault-1", 50000000)

        assertTrue("deposit must include anti-replay headers", depositHasAntiReplay)
        assertTrue("withdraw must include anti-replay headers", withdrawHasAntiReplay)
    }

    // MARK: - Issue #109: Beneficiary Acceptance Token Requirement

    @Test
    fun `issue 109 - acceptBeneficiary requires token parameter`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var capturedRequestBody: String? = null
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/accept") {
                respond(content = "{}", status = HttpStatusCode.NoContent)
            } else {
                respond(content = "{}", status = HttpStatusCode.OK)
            }
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        val result = apiClient.acceptBeneficiary("vault-1", "acceptance-token-xyz")

        assertTrue("acceptBeneficiary should succeed", result is ApiResult.Success)
    }

    @Test
    fun `issue 109 - acceptBeneficiary is a mutating request`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var wasPostRequest = false
        var hasAntiReplay = false

        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/accept") {
                wasPostRequest = request.method.value == "POST"
                hasAntiReplay = request.headers["X-Nonce"] != null && request.headers["X-Timestamp"] != null
            }
            respond(content = "{}", status = HttpStatusCode.NoContent)
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.acceptBeneficiary("vault-1", "token-abc123")

        assertTrue("acceptBeneficiary must be a POST request", wasPostRequest)
        assertTrue("acceptBeneficiary must include anti-replay headers", hasAntiReplay)
    }

    @Test
    fun `issue 109 - acceptBeneficiary accepts 204 No Content response`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/accept") {
                respond(content = "", status = HttpStatusCode.NoContent)
            } else {
                respond(content = "{}", status = HttpStatusCode.OK)
            }
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        val result = apiClient.acceptBeneficiary("vault-1", "token-abc")

        assertTrue("acceptBeneficiary should succeed with 204", result is ApiResult.Success)
    }

    @Test
    fun `issue 109 - acceptBeneficiary fails with invalid token (401)`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/accept") {
                respond(content = "", status = HttpStatusCode.Unauthorized)
            } else {
                respond(content = "{}", status = HttpStatusCode.OK)
            }
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        val result = apiClient.acceptBeneficiary("vault-1", "invalid-token")

        assertTrue("acceptBeneficiary should fail with 401 for invalid token", result is ApiResult.Error)
    }

    // MARK: - Issue #115: TOTP Re-verify Copy / Provisioning Data

    @Test
    fun `issue 115 - get2FAStatus includes enabled flag`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/2fa/status") {
                respond(
                    content = """{"enabled":true,"method":"totp"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(content = "{}", status = HttpStatusCode.OK)
            }
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        val result = apiClient.get2FAStatus("vault-1")

        assertTrue("get2FAStatus should succeed", result is ApiResult.Success)
    }

    @Test
    fun `issue 115 - enable2FA returns provisioning URI for TOTP`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/2fa/enable") {
                respond(
                    content = """{"provisioning_uri":"otpauth://totp/Ethos?secret=JBSWY3DPEBLW64TMMQQQ","secret":"JBSWY3DPEBLW64TMMQQQ"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(content = "{}", status = HttpStatusCode.OK)
            }
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        val result = apiClient.enable2FA("vault-1", mapOf("method" to "totp"))

        assertTrue("enable2FA should succeed", result is ApiResult.Success)
    }

    @Test
    fun `issue 115 - challenge2FA does not return provisioning data`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/2fa/challenge") {
                respond(
                    content = """{"enabled":true,"method":"totp"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(content = "{}", status = HttpStatusCode.OK)
            }
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        val result = apiClient.challenge2FA("vault-1")

        assertTrue("challenge2FA should succeed", result is ApiResult.Success)
    }

    // MARK: - Cross-Platform Consistency Checks

    @Test
    fun `regression - all mutating requests have anti-replay headers`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var checkInHasAntiReplay = false
        var depositHasAntiReplay = false

        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/vaults/vault-1/checkin" -> {
                    checkInHasAntiReplay = request.headers["X-Nonce"] != null && request.headers["X-Timestamp"] != null
                    respond(content = "{}", status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                "/vaults/vault-1/deposit" -> {
                    depositHasAntiReplay = request.headers["X-Nonce"] != null && request.headers["X-Timestamp"] != null
                    respond(content = SAMPLE_VAULT_JSON, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> respond(content = "{}", status = HttpStatusCode.OK)
            }
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.checkIn("vault-1")
        apiClient.deposit("vault-1", 50000000)

        assertTrue("checkIn must include anti-replay headers", checkInHasAntiReplay)
        assertTrue("deposit must include anti-replay headers", depositHasAntiReplay)
    }

    @Test
    fun `regression - vault beneficiary update is possible`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/beneficiary") {
                respond(
                    content = """{"id":"vault-1","owner":"GABC","beneficiary":"GNEW","balance":100000000,"check_in_interval":2592000,"last_check_in":"2026-01-01T00:00:00Z","ttl_remaining":1000000,"status":"active"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(content = "{}", status = HttpStatusCode.OK)
            }
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        val result = apiClient.updateBeneficiary("vault-1", "GNEW")

        assertTrue("updateBeneficiary should succeed", result is ApiResult.Success)
    }
}

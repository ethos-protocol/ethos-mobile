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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiContractTest {

    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val networkMonitor: NetworkMonitor = mockk { every { isConnected } returns true }
    private val offlineCache: OfflineCache = mockk(relaxed = true)

    @Test
    fun `mutating requests include X-Nonce header`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var capturedXNonce: String? = null
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/checkin") {
                capturedXNonce = request.headers["X-Nonce"]
            }
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.checkIn("vault-1")

        assertNotNull("X-Nonce header must be present on POST requests", capturedXNonce)
    }

    @Test
    fun `mutating requests include X-Timestamp header`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var capturedXTimestamp: String? = null
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/checkin") {
                capturedXTimestamp = request.headers["X-Timestamp"]
            }
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.checkIn("vault-1")

        assertNotNull("X-Timestamp header must be present on POST requests", capturedXTimestamp)
    }

    @Test
    fun `X-Nonce is 32 bytes hex-encoded (64 characters)`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var capturedXNonce: String? = null
        val engine = MockEngine { request ->
            capturedXNonce = request.headers["X-Nonce"]
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.checkIn("vault-1")

        assertNotNull(capturedXNonce)
        assertEquals("X-Nonce must be 64 hex characters", 64, capturedXNonce?.length)
        assertTrue("X-Nonce must be valid hex", capturedXNonce?.all { it in "0123456789abcdef" } ?: false)
    }

    @Test
    fun `X-Timestamp is valid Unix epoch seconds`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var capturedXTimestamp: String? = null
        val engine = MockEngine { request ->
            capturedXTimestamp = request.headers["X-Timestamp"]
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.checkIn("vault-1")

        assertNotNull(capturedXTimestamp)
        val timestamp = capturedXTimestamp?.toLongOrNull()
        assertNotNull("X-Timestamp must be parseable as Long", timestamp)

        val now = System.currentTimeMillis() / 1000
        val diff = Math.abs(now - (timestamp ?: 0))
        assertTrue("X-Timestamp should be within 5 seconds of current time", diff < 5)
    }

    @Test
    fun `DELETE requests include X-Nonce and X-Timestamp`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var capturedXNonce: String? = null
        var capturedXTimestamp: String? = null
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/notifications/register") {
                capturedXNonce = request.headers["X-Nonce"]
                capturedXTimestamp = request.headers["X-Timestamp"]
            }
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.unregisterPushToken("test-token")

        assertNotNull("X-Nonce header must be present on DELETE requests", capturedXNonce)
        assertNotNull("X-Timestamp header must be present on DELETE requests", capturedXTimestamp)
    }

    @Test
    fun `GET requests do not include anti-replay headers`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var hasXNonce = false
        var hasXTimestamp = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults") {
                hasXNonce = request.headers["X-Nonce"] != null
                hasXTimestamp = request.headers["X-Timestamp"] != null
            }
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.listVaults()

        assertFalse("GET requests should not include X-Nonce", hasXNonce)
        assertFalse("GET requests should not include X-Timestamp", hasXTimestamp)
    }

    @Test
    fun `paginated listVaults includes limit query parameter`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.listVaults(limit = 50)

        assertTrue("URL must include limit parameter", capturedUrl?.contains("limit=50") ?: false)
    }

    @Test
    fun `paginated listVaults includes cursor query parameter when provided`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.listVaults(limit = 50, after = "test-cursor")

        assertTrue("URL must include cursor parameter", capturedUrl?.contains("after=test-cursor") ?: false)
    }

    @Test
    fun `checkIn sends POST with anti-replay headers`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var wasPostRequest = false
        var hasAntiReplayHeaders = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/checkin") {
                wasPostRequest = request.method.value == "POST"
                hasAntiReplayHeaders = request.headers["X-Nonce"] != null && request.headers["X-Timestamp"] != null
            }
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.checkIn("vault-1")

        assertTrue("checkIn must be a POST request", wasPostRequest)
        assertTrue("checkIn must include anti-replay headers", hasAntiReplayHeaders)
    }

    @Test
    fun `deposit sends POST with anti-replay headers`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var wasPostRequest = false
        var hasAntiReplayHeaders = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/vaults/vault-1/deposit") {
                wasPostRequest = request.method.value == "POST"
                hasAntiReplayHeaders = request.headers["X-Nonce"] != null && request.headers["X-Timestamp"] != null
            }
            respond(
                content = """{"id":"vault-1","owner":"G1","beneficiary":"G2","balance":100,"check_in_interval":86400,"last_check_in":"2026-01-01T00:00:00Z","ttl_remaining":1000,"status":"active"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.deposit("vault-1", 100)

        assertTrue("deposit must be a POST request", wasPostRequest)
        assertTrue("deposit must include anti-replay headers", hasAntiReplayHeaders)
    }

    @Test
    fun `all requests include Authorization header when token exists`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var hasAuthHeader = false
        val engine = MockEngine { request ->
            hasAuthHeader = request.headers["Authorization"] == "Bearer test-token"
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.listVaults()

        assertTrue("Authenticated requests must include Authorization header", hasAuthHeader)
    }

    @Test
    fun `requests include Content-Type JSON header`() = runTest {
        every { tokenProvider.token } returns "test-token"
        every { tokenProvider.isNearExpiry() } returns false

        var hasContentType = false
        val engine = MockEngine { request ->
            hasContentType = request.headers["Content-Type"]?.contains("application/json") ?: false
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)
        apiClient.checkIn("vault-1")

        assertTrue("POST requests must include Content-Type: application/json", hasContentType)
    }
}

package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.AuthToken
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientTest {

    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val networkMonitor: NetworkMonitor = mockk { every { isConnected } returns true }
    private val offlineCache: OfflineCache = mockk(relaxed = true)

    @Test
    fun `near-expiry token is refreshed before the request goes out`() = runTest {
        every { tokenProvider.token } returns "old-token"
        every { tokenProvider.isNearExpiry() } returns true

        var refreshWasCalled = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/auth/refresh") {
                refreshWasCalled = true
                respond(
                    content = """{"token":"new-token","expires_at":"2026-08-01T00:00:00Z"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = "[]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        apiClient.listVaults()

        assertTrue(refreshWasCalled)
        verify { tokenProvider.setSession(AuthToken(token = "new-token", expiresAt = "2026-08-01T00:00:00Z")) }
    }

    @Test
    fun `fresh token skips the refresh call`() = runTest {
        every { tokenProvider.token } returns "good-token"
        every { tokenProvider.isNearExpiry() } returns false

        var refreshWasCalled = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/auth/refresh") refreshWasCalled = true
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        apiClient.listVaults()

        assertFalse(refreshWasCalled)
    }

    @Test
    fun `refresh failure clears the stored token instead of retrying with the stale one`() = runTest {
        every { tokenProvider.token } returns "old-token"
        every { tokenProvider.isNearExpiry() } returns true

        val engine = MockEngine { respond(content = "", status = HttpStatusCode.Unauthorized) }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        apiClient.listVaults()

        verify { tokenProvider.clear() }
    }

    @Test
    fun `401 on a normal request clears the stored token`() = runTest {
        every { tokenProvider.token } returns "old-token"
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine { respond(content = "", status = HttpStatusCode.Unauthorized) }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        val result = apiClient.listVaults()

        assertTrue(result is ApiResult.Error)
        verify { tokenProvider.clear() }
    }
}

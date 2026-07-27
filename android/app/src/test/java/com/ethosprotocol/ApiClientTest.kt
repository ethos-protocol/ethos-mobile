package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.api.RetryPolicy
import com.ethosprotocol.api.TokenProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ApiClientTest {

    private val tokenProvider: TokenProvider = mockk { every { token } returns "test-token" }
    private val networkMonitor: NetworkMonitor = mockk { every { isConnected } returns true }
    private val offlineCache: OfflineCache = mockk(relaxed = true)

    private val challengeJson = """{"challenge":"abc","expires_at":"2026-01-01T00:00:00Z"}"""

    private fun client(engine: MockEngine, retryPolicy: RetryPolicy) = ApiClient(
        tokenProvider, networkMonitor, offlineCache, "https://api.test", engine, retryPolicy
    )

    @Test
    fun `get retries transient failures with exponential backoff and succeeds on the final attempt`() = runTest {
        val callCount = AtomicInteger(0)
        val delays = mutableListOf<Long>()
        val engine = MockEngine {
            if (callCount.getAndIncrement() < 2) throw IOException("connection reset")
            respond(challengeJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 500, sleep = { delays.add(it) })

        val result = client(engine, policy).getChallenge()

        assertTrue(result is ApiResult.Success)
        assertEquals(3, callCount.get())
        assertEquals(listOf(500L, 1000L), delays)
    }

    @Test
    fun `get exhausts retry budget and surfaces an error without exceeding maxAttempts`() = runTest {
        val callCount = AtomicInteger(0)
        val delays = mutableListOf<Long>()
        val engine = MockEngine {
            callCount.incrementAndGet()
            throw IOException("connection reset")
        }
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 500, sleep = { delays.add(it) })

        val result = client(engine, policy).getChallenge()

        assertTrue(result is ApiResult.Error)
        assertEquals(3, callCount.get())
        assertEquals(listOf(500L, 1000L), delays)
    }

    @Test
    fun `get does not retry non-transient errors`() = runTest {
        val callCount = AtomicInteger(0)
        val delays = mutableListOf<Long>()
        val engine = MockEngine {
            callCount.incrementAndGet()
            throw IllegalStateException("boom")
        }
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 500, sleep = { delays.add(it) })

        val result = client(engine, policy).getChallenge()

        assertTrue(result is ApiResult.Error)
        assertEquals(1, callCount.get())
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `post never retries even on a transient failure`() = runTest {
        val callCount = AtomicInteger(0)
        val delays = mutableListOf<Long>()
        val engine = MockEngine {
            callCount.incrementAndGet()
            throw IOException("connection reset")
        }
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 500, sleep = { delays.add(it) })

        val result = client(engine, policy).checkIn("vault-1")

        assertTrue(result is ApiResult.Error)
        assertEquals(1, callCount.get())
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `delete never retries even on a transient failure`() = runTest {
        val callCount = AtomicInteger(0)
        val delays = mutableListOf<Long>()
        val engine = MockEngine {
            callCount.incrementAndGet()
            throw IOException("connection reset")
        }
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 500, sleep = { delays.add(it) })

        val result = client(engine, policy).unregisterPushToken("push-token")

        assertTrue(result is ApiResult.Error)
        assertEquals(1, callCount.get())
        assertTrue(delays.isEmpty())
    }
}

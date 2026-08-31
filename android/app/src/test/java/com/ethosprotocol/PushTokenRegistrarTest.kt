package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.RetryPolicy
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.services.PushTokenRegistrar
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers #234: registration retries with backoff on failure, and — if every
 * attempt fails — persists the token as "pending" so a later foreground can
 * retry it, instead of silently dropping it (the previous fire-and-forget
 * behavior in PushService.onNewToken).
 */
class PushTokenRegistrarTest {

    /** In-memory token holder matching the real [TokenProvider] contract. */
    private class FakeTokenProvider : TokenProvider {
        override var token: String? = null
        override var pushToken: String? = null
        override var pendingPushToken: String? = null
        override fun clear() { token = null }
    }

    private fun fastPolicy(maxAttempts: Int) = RetryPolicy(
        maxAttempts = maxAttempts,
        baseDelayMillis = 1,
        sleep = { }
    )

    @Test
    fun `registerSuspending succeeds after transient failures, saves token and clears pending`() = runTest {
        val tokenProvider = FakeTokenProvider()
        tokenProvider.pendingPushToken = "stale-token"
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        registrar.retryPolicy = fastPolicy(maxAttempts = 3)
        var attempts = 0
        registrar.registerCall = { _ ->
            attempts++
            if (attempts < 3) ApiResult.Error("boom", 500) else ApiResult.Success(Unit)
        }

        registrar.registerSuspending("token-abc")

        assertEquals(3, attempts)
        assertEquals("token-abc", tokenProvider.pushToken)
        assertNull("pending token should be cleared on success", tokenProvider.pendingPushToken)
    }

    @Test
    fun `registerSuspending exhausts retries, persists pending token`() = runTest {
        val tokenProvider = FakeTokenProvider()
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        registrar.retryPolicy = fastPolicy(maxAttempts = 3)
        registrar.registerCall = { ApiResult.NetworkUnavailable }

        registrar.registerSuspending("token-xyz")

        assertNull("should not be marked registered on failure", tokenProvider.pushToken)
        assertEquals("a failed registration must be persisted for a later foreground retry",
            "token-xyz", tokenProvider.pendingPushToken)
    }

    @Test
    fun `retryPendingIfNeeded does nothing when no token is pending`() = runTest {
        val tokenProvider = FakeTokenProvider()
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        var wasCalled = false
        registrar.registerCall = { wasCalled = true; ApiResult.Success(Unit) }

        registrar.retryPendingIfNeeded(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

        assertFalse(wasCalled)
    }

    @Test
    fun `retryPendingIfNeeded retries the pending token and clears it on success`() = runTest {
        val tokenProvider = FakeTokenProvider()
        tokenProvider.pendingPushToken = "pending-token"
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        registrar.retryPolicy = fastPolicy(maxAttempts = 1)
        var registered: String? = null
        registrar.registerCall = { token -> registered = token; ApiResult.Success(Unit) }

        registrar.retryPendingIfNeeded(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

        assertEquals("pending-token", registered)
        assertEquals("pending-token", tokenProvider.pushToken)
        assertNull(tokenProvider.pendingPushToken)
    }
}

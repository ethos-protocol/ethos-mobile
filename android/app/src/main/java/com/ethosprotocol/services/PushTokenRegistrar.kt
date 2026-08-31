package com.ethosprotocol.services

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.RetryPolicy
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.api.withRetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers a device's FCM token with the server, retrying with backoff on
 * failure (#234) — `PushService.onNewToken` previously fired the request and
 * ignored the outcome entirely, so a network blip or server 5xx could
 * silently stop the app from receiving push notifications until the next
 * token rotation.
 *
 * Registration is idempotent server-side (re-registering the same token is a
 * no-op), which is why — unlike ApiClient's general policy of never
 * auto-retrying a mutation — retrying this one specifically is safe.
 *
 * If every retry attempt fails, the token is persisted as "pending" via
 * [TokenProvider.pendingPushToken] rather than dropped, so [retryPendingIfNeeded]
 * can pick it back up the next time the app foregrounds (see MainActivity's
 * `onResume`) instead of silently waiting on Firebase to redeliver the token.
 */
@Singleton
class PushTokenRegistrar @Inject constructor(
    private val apiClient: ApiClient,
    private val tokenProvider: TokenProvider
) {
    var retryPolicy: RetryPolicy = RetryPolicy.networkDefault

    // Injectable for testing, so the retry path can be exercised against a
    // mock that fails a controlled number of times instead of a real network call.
    var registerCall: suspend (String) -> ApiResult<Unit> = { apiClient.registerPushToken(it) }

    /** Fire-and-forget entry point for `PushService.onNewToken`. */
    fun register(token: String, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        scope.launch { registerSuspending(token) }
    }

    suspend fun registerSuspending(token: String) {
        try {
            withRetry(retryPolicy, isRetryable = { true }) {
                when (val result = registerCall(token)) {
                    is ApiResult.Success -> Unit
                    else -> throw RegistrationFailedException(result)
                }
            }
            // Persisted only on success so sign-out unregisters a token the
            // server actually has on file for this device.
            tokenProvider.pushToken = token
            tokenProvider.pendingPushToken = null
        } catch (e: RegistrationFailedException) {
            tokenProvider.pendingPushToken = token
        }
    }

    /** Call when the app foregrounds (MainActivity.onResume) (#234). */
    fun retryPendingIfNeeded(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        val pending = tokenProvider.pendingPushToken ?: return
        register(pending, scope)
    }

    private class RegistrationFailedException(val result: ApiResult<Unit>) : Exception()
}

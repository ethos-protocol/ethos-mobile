package com.ethosprotocol.services

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.VaultEvent
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

// Reconnect/backoff schedule for VaultEventSocket. Unlike RetryPolicy (bounded
// attempts for a single request), a dropped socket should keep retrying
// indefinitely with the delay capped so a long outage doesn't leave the client
// waiting minutes between attempts once it reconnects.
data class ReconnectBackoff(
    val baseDelayMillis: Long,
    val maxDelayMillis: Long,
    val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    fun delayForAttempt(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 20)
        return (baseDelayMillis * (1L shl shift)).coerceAtMost(maxDelayMillis)
    }

    companion object {
        val default = ReconnectBackoff(baseDelayMillis = 1_000, maxDelayMillis = 30_000)
    }
}

// Client for the `wss://.../ws?vault_id={id}` endpoint (shared/api-contract.md).
// Reuses ApiClient's HttpClient/WebSockets plugin rather than a second client.
// A dropped or failed connection reconnects with exponential backoff
// ([ReconnectBackoff]) until the collecting coroutine is cancelled; the backoff
// resets once a new connection is established.
@Singleton
class VaultEventSocket(
    private val apiClient: ApiClient,
    private val tokenProvider: TokenProvider,
    private val backoff: ReconnectBackoff = ReconnectBackoff.default
) {
    // Distinct @Inject constructor for the same reason as ApiClient's: Dagger's
    // codegen calls the full-arg constructor directly and ignores Kotlin default
    // values, so Hilt needs an explicit constructor matching only its bindings.
    @Inject constructor(apiClient: ApiClient, tokenProvider: TokenProvider) :
        this(apiClient, tokenProvider, ReconnectBackoff.default)

    // internal (not private): tests replace this to simulate connect failures/frames
    // without a real server, matching how ApiClient exposes its engine to tests.
    internal var openSession: suspend (String) -> WebSocketSession = { vaultId ->
        apiClient.client.webSocketSession {
            url(apiClient.webSocketUrl(vaultId))
            tokenProvider.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }

    fun events(vaultId: String): Flow<VaultEvent> = flow {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                val session = openSession(vaultId)
                attempt = 0
                for (frame in session.incoming) {
                    if (frame is Frame.Text) {
                        runCatching { Json.decodeFromString<VaultEvent>(frame.readText()) }
                            .onSuccess { emit(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Connection failed or dropped — fall through to backoff and reconnect.
            }
            if (!currentCoroutineContext().isActive) break
            backoff.sleep(backoff.delayForAttempt(attempt))
            attempt++
        }
    }
}

package com.ethosprotocol.services

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.VaultEvent
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.closeReason
import io.ktor.websocket.readText
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
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
//
// delayForAttempt applies full jitter — the returned delay is chosen uniformly from
// [0, cappedDelay) rather than being the deterministic capped value itself — so that
// many sockets dropped by the same outage don't all reconnect in lockstep and hit the
// recovering server at the same instants.
data class ReconnectBackoff(
    val baseDelayMillis: Long,
    val maxDelayMillis: Long,
    val sleep: suspend (Long) -> Unit = { delay(it) },
    // Source of randomness for jitter. Injectable so tests can supply a
    // seeded/deterministic Random instead of the real one.
    val random: Random = Random.Default
) {
    fun delayForAttempt(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 20)
        val cappedDelay = (baseDelayMillis * (1L shl shift)).coerceAtMost(maxDelayMillis)
        return if (cappedDelay > 0) random.nextLong(0, cappedDelay) else 0L
    }

    companion object {
        val default = ReconnectBackoff(baseDelayMillis = 1_000, maxDelayMillis = 30_000)
    }
}

// Sentinel used by events() to signal that a 4401 close was received and the
// silent-refresh path should be entered instead of the normal backoff reconnect.
private class Auth4401Exception : Exception("WebSocket closed with code 4401 (auth failure)")

// Client for the `wss://.../ws?vault_id={id}` endpoint (shared/api-contract.md).
// Reuses ApiClient's HttpClient/WebSockets plugin rather than a second client.
// A dropped or failed connection reconnects with exponential backoff
// ([ReconnectBackoff]) until the collecting coroutine is cancelled; the backoff
// resets once a new connection is established.
//
// #257 — 4401 handling:
// When the server closes the socket with code 4401 (authentication failure), the
// client distinguishes two cases:
//   • Expired token (refreshable): attempt one silent refresh via ApiClient.refreshToken()
//     and reconnect if it succeeds. This covers the normal JWT-expiry-mid-connection case.
//   • Invalid / revoked token: if the refresh call itself fails (e.g. the server returns
//     401 on the refresh endpoint), give up and emit the special `authFailure` event so
//     the UI can route the user back to the sign-in screen.
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

    // Injectable for tests so they can simulate a successful or failing refresh
    // without hitting a real server.
    internal var refreshToken: suspend () -> ApiResult<com.ethosprotocol.models.AuthToken> = {
        apiClient.refreshToken()
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
                // Check close reason after the incoming channel drains.
                val closeReason = session.closeReason.await()
                if (closeReason?.code?.toInt() == 4401) {
                    throw Auth4401Exception()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Auth4401Exception) {
                // #257: The server closed with 4401 (auth failure). Attempt one silent
                // token refresh before deciding whether to reconnect or signal sign-out.
                val refreshResult = runCatching { refreshToken() }.getOrElse {
                    if (it is CancellationException) throw it
                    ApiResult.Error("refresh call threw", 0)
                }
                when (refreshResult) {
                    is ApiResult.Success -> {
                        // Refresh succeeded — store the new token and reconnect.
                        tokenProvider.setSession(refreshResult.data)
                        attempt = 0
                        // No backoff delay; reconnect immediately with the fresh token.
                        continue
                    }
                    else -> {
                        // Refresh failed — the token is invalid, not just expired.
                        // Emit an authFailure sentinel event so the UI can sign the user out.
                        emit(VaultEvent(type = "auth_failure", vault = null))
                        return@flow
                    }
                }
            } catch (e: Exception) {
                // Connection failed or dropped — fall through to backoff and reconnect.
            }
            if (!currentCoroutineContext().isActive) break
            backoff.sleep(backoff.delayForAttempt(attempt))
            attempt++
        }
    }
}

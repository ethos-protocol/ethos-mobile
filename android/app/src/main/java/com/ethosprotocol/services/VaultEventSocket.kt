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
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

// Connection state for the WebSocket. FALLBACK_TO_POLLING is included for parity
// with the iOS API contract; on Android the socket retries indefinitely unless
// maxReconnectAttempts is set to a finite value.
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FALLBACK_TO_POLLING }

// Client for the `wss://.../ws?vault_id={id}` endpoint (shared/api-contract.md).
// Reuses ApiClient's HttpClient/WebSockets plugin rather than a second client.
// A dropped or failed connection reconnects with exponential backoff
// ([ReconnectBackoff]) until the collecting coroutine is cancelled; the backoff
// resets once a new connection is established.
@Singleton
class VaultEventSocket(
    private val apiClient: ApiClient,
    private val tokenProvider: TokenProvider,
    private val backoff: ReconnectBackoff = ReconnectBackoff.default,
    val heartbeatIntervalMillis: Long = 30_000L,
    val maxReconnectAttempts: Int = Int.MAX_VALUE
) {
    // Distinct @Inject constructor for the same reason as ApiClient's: Dagger's
    // codegen calls the full-arg constructor directly and ignores Kotlin default
    // values, so Hilt needs an explicit constructor matching only its bindings.
    @Inject constructor(apiClient: ApiClient, tokenProvider: TokenProvider) :
        this(apiClient, tokenProvider, ReconnectBackoff.default)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

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
            _connectionState.value = ConnectionState.CONNECTING
            try {
                val session = openSession(vaultId)
                attempt = 0
                _connectionState.value = ConnectionState.CONNECTED
                coroutineScope {
                    // Periodic client-side heartbeat to detect silent TCP drops.
                    launch {
                        while (isActive) {
                            delay(heartbeatIntervalMillis)
                            try {
                                session.send(Frame.Text("""{ "type": "ping" }"""))
                            } catch (_: Exception) {
                                // Send failed — session is dead; cancel scope to stop incoming loop too.
                                cancel()
                            }
                        }
                    }
                    for (frame in session.incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            runCatching { Json.decodeFromString<VaultEvent>(text) }
                                .onSuccess { event ->
                                    if (event.type == "ping") {
                                        // Server keepalive — reply with pong.
                                        runCatching { session.send(Frame.Text("""{ "type": "pong" }""")) }
                                    }
                                    emit(event)
                                }
                        }
                    }
                    cancel() // incoming closed normally — stop heartbeat too
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Connection failed or dropped — fall through to backoff and reconnect.
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            if (!currentCoroutineContext().isActive) break
            if (attempt >= maxReconnectAttempts) {
                _connectionState.value = ConnectionState.FALLBACK_TO_POLLING
                break
            }
            backoff.sleep(backoff.delayForAttempt(attempt))
            attempt++
        }
    }

    fun events(vaultIds: List<String>): Flow<VaultEvent> = flow {
        if (vaultIds.isEmpty()) return@flow
        // Use the first vault ID as the primary connection URL, then send a subscribe
        // message for the rest once connected.
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _connectionState.value = ConnectionState.CONNECTING
            try {
                val session = openSession(vaultIds[0])
                attempt = 0
                _connectionState.value = ConnectionState.CONNECTED
                // Send multi-vault subscribe message post-connect
                if (vaultIds.size > 1) {
                    val idsJson = vaultIds.joinToString(",") { "\"$it\"" }
                    runCatching { session.send(Frame.Text("""{ "type": "subscribe", "vault_ids": [$idsJson] }""")) }
                }
                coroutineScope {
                    launch {
                        while (isActive) {
                            delay(heartbeatIntervalMillis)
                            try {
                                session.send(Frame.Text("""{ "type": "ping" }"""))
                            } catch (_: Exception) {
                                cancel()
                            }
                        }
                    }
                    for (frame in session.incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            runCatching { Json.decodeFromString<VaultEvent>(text) }
                                .onSuccess { event ->
                                    if (event.type == "ping") {
                                        runCatching { session.send(Frame.Text("""{ "type": "pong" }""")) }
                                    }
                                    emit(event)
                                }
                        }
                    }
                    cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // connection failed
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            if (!currentCoroutineContext().isActive) break
            if (attempt >= maxReconnectAttempts) {
                _connectionState.value = ConnectionState.FALLBACK_TO_POLLING
                break
            }
            backoff.sleep(backoff.delayForAttempt(attempt))
            attempt++
        }
    }
}

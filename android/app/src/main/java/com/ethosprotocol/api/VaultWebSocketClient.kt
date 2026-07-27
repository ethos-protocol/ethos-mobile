package com.ethosprotocol.api

// #110 — WebSocket event stream stub.
// Message schema defined in shared/api-contract.md §WebSocket Message Schema.

import com.ethosprotocol.models.Vault
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

// MARK: - WebSocket event types

sealed class VaultWebSocketEvent {
    /** Server-side vault state changed (TTL refresh, check-in, deposit, etc.). */
    data class VaultUpdated(val vaultId: String, val vault: Vault) : VaultWebSocketEvent()
    /** Vault transitioned to `expired` status. */
    data class VaultExpired(val vaultId: String, val expiredAt: String) : VaultWebSocketEvent()
    /** Funds released to the beneficiary. */
    data class VaultReleased(val vaultId: String, val releasedAt: String, val amount: Long) : VaultWebSocketEvent()
    /** Server keepalive — no action required; client may reply with `pong`. */
    object Ping : VaultWebSocketEvent()
    /** Server signalled a recoverable error. */
    data class Error(val code: String, val message: String) : VaultWebSocketEvent()
    /** Connection closed; will reconnect unless code == 4401. */
    data class Disconnected(val reason: String) : VaultWebSocketEvent()
}

// MARK: - Raw server message envelopes (internal, for decoding only)

@Serializable
private data class WSVaultUpdatedMessage(
    @SerialName("vault_id") val vaultId: String,
    val vault: Vault
)

@Serializable
private data class WSVaultExpiredMessage(
    @SerialName("vault_id") val vaultId: String,
    @SerialName("expired_at") val expiredAt: String
)

@Serializable
private data class WSVaultReleasedMessage(
    @SerialName("vault_id") val vaultId: String,
    @SerialName("released_at") val releasedAt: String,
    val amount: Long
)

@Serializable
private data class WSErrorMessage(
    val code: String,
    val message: String
)

// MARK: - VaultWebSocketClient

/**
 * Stub WebSocket client for real-time vault events (#110).
 *
 * Emits a [Flow] of [VaultWebSocketEvent]s. Reconnects with exponential backoff
 * (1 s base, 60 s cap) on any non-4401 close.
 *
 * NOTE: This is a stub — the connection logic is correct but no staging WebSocket
 * endpoint exists yet. Wire up the Flow into [VaultViewModel] once it does.
 *
 * Usage:
 * ```kotlin
 * wsClient.events(vaultId = "vault-123").collect { event ->
 *     when (event) { ... }
 * }
 * ```
 */
@Singleton
class VaultWebSocketClient @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val baseUrl: String
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient(Android) {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(json)
        }
    }

    // Reconnect backoff: doubles each attempt, capped at maxBackoffMs.
    private val baseBackoffMs = 1_000L
    private val maxBackoffMs = 60_000L

    /**
     * Returns a cold [Flow] that connects to the vault WebSocket, emits events,
     * and reconnects automatically on transient failures.
     */
    fun events(vaultId: String): Flow<VaultWebSocketEvent> = callbackFlow {
        var backoffMs = baseBackoffMs
        while (isActive) {
            try {
                val wsUrl = baseUrl
                    .replace("https://", "wss://")
                    .replace("http://", "ws://") + "/ws?vault_id=$vaultId"

                client.webSocket(urlString = wsUrl, request = {
                    tokenProvider.token?.let { headers.append("Authorization", "Bearer $it") }
                }) {
                    backoffMs = baseBackoffMs // reset on successful connection

                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val event = parseEvent(frame.readText())
                                if (event != null) send(event)
                                // Reply to keepalive pings
                                if (event == VaultWebSocketEvent.Ping) {
                                    outgoing.trySend(Frame.Text("""{"type":"pong"}"""))
                                }
                            }
                            is Frame.Close -> {
                                val reason = frame.readReason()
                                send(VaultWebSocketEvent.Disconnected(reason?.message ?: "closed"))
                                // Code 4401 == auth failure — do not reconnect
                                if (reason?.code?.toInt() == 4401) {
                                    close()
                                    return@webSocket
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                send(VaultWebSocketEvent.Disconnected(e.message ?: "error"))
            }

            if (!isActive) break
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, maxBackoffMs)
        }
        awaitClose { client.close() }
    }

    // MARK: - Private

    private fun parseEvent(text: String): VaultWebSocketEvent? {
        return try {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
            when (obj["type"]?.jsonPrimitive?.content) {
                "vault_updated" -> {
                    val msg = json.decodeFromString<WSVaultUpdatedMessage>(text)
                    VaultWebSocketEvent.VaultUpdated(vaultId = msg.vaultId, vault = msg.vault)
                }
                "vault_expired" -> {
                    val msg = json.decodeFromString<WSVaultExpiredMessage>(text)
                    VaultWebSocketEvent.VaultExpired(vaultId = msg.vaultId, expiredAt = msg.expiredAt)
                }
                "vault_released" -> {
                    val msg = json.decodeFromString<WSVaultReleasedMessage>(text)
                    VaultWebSocketEvent.VaultReleased(
                        vaultId = msg.vaultId, releasedAt = msg.releasedAt, amount = msg.amount
                    )
                }
                "ping" -> VaultWebSocketEvent.Ping
                "error" -> {
                    val msg = json.decodeFromString<WSErrorMessage>(text)
                    VaultWebSocketEvent.Error(code = msg.code, message = msg.message)
                }
                else -> null // unknown type — ignore forward-compatible
            }
        } catch (_: Exception) {
            null
        }
    }
}

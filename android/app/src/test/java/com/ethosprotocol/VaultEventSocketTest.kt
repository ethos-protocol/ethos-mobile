package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.VaultEvent
import com.ethosprotocol.services.ReconnectBackoff
import com.ethosprotocol.services.VaultEventSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class VaultEventSocketTest {

    private val apiClient: ApiClient = mockk(relaxed = true)
    private val tokenProvider: TokenProvider = mockk { every { token } returns "test-token" }

    @Test
    fun `delayForAttempt doubles each attempt and caps at maxDelayMillis`() {
        val backoff = ReconnectBackoff(baseDelayMillis = 1_000, maxDelayMillis = 30_000)

        assertEquals(1_000L, backoff.delayForAttempt(0))
        assertEquals(2_000L, backoff.delayForAttempt(1))
        assertEquals(4_000L, backoff.delayForAttempt(2))
        assertEquals(8_000L, backoff.delayForAttempt(3))
        assertEquals(30_000L, backoff.delayForAttempt(10))
    }

    @Test
    fun `events reconnects with backoff after a connection failure and resumes emitting`() = runTest {
        val delays = mutableListOf<Long>()
        val backoff = ReconnectBackoff(baseDelayMillis = 1_000, maxDelayMillis = 30_000, sleep = { delays.add(it) })
        val socket = VaultEventSocket(apiClient, tokenProvider, backoff)

        val openAttempts = AtomicInteger(0)
        val channel = Channel<Frame>(capacity = 1)
        val event = VaultEvent(type = "check_in", vault = null)
        channel.trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), event)))

        socket.openSession = {
            if (openAttempts.getAndIncrement() == 0) throw IOException("connection refused")
            mockk<WebSocketSession> { every { incoming } returns channel }
        }

        val received = socket.events("vault-1").take(1).toList()

        assertEquals(listOf(event), received)
        assertEquals(2, openAttempts.get())
        assertEquals(listOf(1_000L), delays)
    }

    // #232: a real vault_expired/vault_released payload carries fields VaultEvent
    // doesn't model (expired_at/released_at/amount) — decoding must tolerate them
    // rather than silently dropping the frame, or the dedup logic in
    // VaultViewModel.subscribeToEvents never even sees these event types.
    @Test
    fun `events decodes a vault_expired frame with fields VaultEvent does not model`() = runTest {
        val backoff = ReconnectBackoff(baseDelayMillis = 1_000, maxDelayMillis = 30_000, sleep = { })
        val socket = VaultEventSocket(apiClient, tokenProvider, backoff)

        val rawFrame = """{"type":"vault_expired","vault_id":"vault-1","expired_at":"2026-01-01T00:00:00Z"}"""
        val channel = Channel<Frame>(capacity = 1)
        channel.trySend(Frame.Text(rawFrame))
        socket.openSession = { mockk<WebSocketSession> { every { incoming } returns channel } }

        val received = socket.events("vault-1").take(1).toList()

        assertEquals(listOf(VaultEvent(type = "vault_expired", vault = null, vaultId = "vault-1")), received)
    }

    @Test
    fun `events resets the backoff attempt counter after a successful connection`() = runTest {
        val delays = mutableListOf<Long>()
        val backoff = ReconnectBackoff(baseDelayMillis = 1_000, maxDelayMillis = 30_000, sleep = { delays.add(it) })
        val socket = VaultEventSocket(apiClient, tokenProvider, backoff)

        // Sequence: fail, succeed-then-immediately-close (no frames), fail, succeed-with-a-frame.
        val openAttempts = AtomicInteger(0)
        val event = VaultEvent(type = "check_in", vault = null)
        socket.openSession = {
            when (openAttempts.getAndIncrement()) {
                0 -> throw IOException("connection refused")
                1 -> mockk<WebSocketSession> { every { incoming } returns Channel<Frame>().apply { close() } }
                2 -> throw IOException("connection refused")
                else -> mockk<WebSocketSession> {
                    every { incoming } returns Channel<Frame>(capacity = 1).apply {
                        trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), event)))
                    }
                }
            }
        }

        val received = socket.events("vault-1").take(1).toList()

        assertEquals(listOf(event), received)
        assertEquals(4, openAttempts.get())
        // open#0 fails at attempt=0 -> delay 1000ms, attempt->1.
        // open#1 connects (then closes with no frames) -> attempt reset to 0 -> delay
        // 1000ms again (not 2000ms), proving the reset, attempt->1.
        // open#2 fails at attempt=1 -> delay 2000ms, attempt->2.
        // open#3 connects and delivers the frame; take(1) ends collection before any
        // further delay is recorded.
        assertEquals(listOf(1_000L, 1_000L, 2_000L), delays)
    }
}

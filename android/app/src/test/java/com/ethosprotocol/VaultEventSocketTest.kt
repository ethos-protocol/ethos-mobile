package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.AuthToken
import com.ethosprotocol.models.VaultEvent
import com.ethosprotocol.services.ReconnectBackoff
import com.ethosprotocol.services.VaultEventSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
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

    // A Random that always returns the requested upper bound minus one, i.e. the
    // largest value the [0, until) contract allows — lets tests assert an exact
    // upper-bound delay without depending on true randomness.
    private fun maxJitterRandom() = object : Random() {
        override fun nextBits(bitCount: Int): Int = Random.Default.nextBits(bitCount)
        override fun nextLong(from: Long, until: Long): Long = until - 1
    }

    @Test
    fun `delayForAttempt doubles the cap each attempt and caps at maxDelayMillis`() {
        val backoff = ReconnectBackoff(baseDelayMillis = 1_000, maxDelayMillis = 30_000, random = maxJitterRandom())

        assertEquals(999L, backoff.delayForAttempt(0))
        assertEquals(1_999L, backoff.delayForAttempt(1))
        assertEquals(3_999L, backoff.delayForAttempt(2))
        assertEquals(7_999L, backoff.delayForAttempt(3))
        assertEquals(29_999L, backoff.delayForAttempt(10))
    }

    @Test
    fun `delayForAttempt never exceeds the deterministic capped delay and never goes negative`() {
        val baseDelayMillis = 1_000L
        val maxDelayMillis = 30_000L
        for (seed in 0 until 500) {
            val backoff = ReconnectBackoff(baseDelayMillis, maxDelayMillis, random = Random(seed.toLong()))
            for (attempt in 0..12) {
                val deterministicDelay = (baseDelayMillis * (1L shl attempt.coerceIn(0, 20))).coerceAtMost(maxDelayMillis)
                val actual = backoff.delayForAttempt(attempt)
                assertTrue("seed=$seed attempt=$attempt actual=$actual", actual in 0 until deterministicDelay)
            }
        }
    }

    @Test
    fun `delayForAttempt with different random sources produces different delays for the same attempt`() {
        val a = ReconnectBackoff(baseDelayMillis = 1_000, maxDelayMillis = 30_000, random = Random(1))
        val b = ReconnectBackoff(baseDelayMillis = 1_000, maxDelayMillis = 30_000, random = Random(2))

        assertNotEquals(a.delayForAttempt(5), b.delayForAttempt(5))
    }

    @Test
    fun `events reconnects with backoff after a connection failure and resumes emitting`() = runTest {
        val delays = mutableListOf<Long>()
        val backoff = ReconnectBackoff(
            baseDelayMillis = 1_000,
            maxDelayMillis = 30_000,
            sleep = { delays.add(it) },
            random = maxJitterRandom()
        )
        val socket = VaultEventSocket(apiClient, tokenProvider, backoff)

        val openAttempts = AtomicInteger(0)
        val channel = Channel<Frame>(capacity = 1)
        val event = VaultEvent(type = "check_in", vault = null)
        channel.trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), event)))

        socket.openSession = {
            if (openAttempts.getAndIncrement() == 0) throw IOException("connection refused")
            mockk<WebSocketSession> {
                every { incoming } returns channel
                every { closeReason } returns CompletableDeferred(null)
            }
        }

        val received = socket.events("vault-1").take(1).toList()

        assertEquals(listOf(event), received)
        assertEquals(2, openAttempts.get())
        assertEquals(listOf(999L), delays)
    }

    // #256 — api-contract.md: "clients should ignore unrecognised values instead of erroring".
    // Sending a made-up future type must not crash and must not surface a VaultEvent (the
    // runCatching in events() silently drops frames it cannot decode, which is the desired
    // behaviour — unknown types are handled at the VaultEvent decode level where the Json
    // deserializer simply skips the unknown discriminator and returns the default null vault).
    @Test
    fun `events ignores unknown type discriminator without crashing`() = runTest {
        val backoff = ReconnectBackoff(
            baseDelayMillis = 1_000,
            maxDelayMillis = 30_000,
            sleep = {},
            random = maxJitterRandom()
        )
        val socket = VaultEventSocket(apiClient, tokenProvider, backoff)

        // Two frames: first has an unrecognised type (must be silently dropped), second is a
        // known type (must be emitted) — so take(1) collects exactly the known event and
        // never blocks.
        val channel = Channel<Frame>(capacity = 2)
        val unknownFrame = Frame.Text("""{"type":"future_event_type_v99","vault":null}""")
        val knownEvent = VaultEvent(type = "check_in", vault = null)
        val knownFrame = Frame.Text(Json.encodeToString(VaultEvent.serializer(), knownEvent))
        channel.trySend(unknownFrame)
        channel.trySend(knownFrame)

        socket.openSession = {
            mockk<WebSocketSession> {
                every { incoming } returns channel
                every { closeReason } returns CompletableDeferred(null)
            }
        }

        // Collecting does not throw; the unknown frame is silently skipped.
        val received = socket.events("vault-1").take(1).toList()
        assertEquals(listOf(knownEvent), received)
    }

    @Test
    fun `events resets the backoff attempt counter after a successful connection`() = runTest {
        val delays = mutableListOf<Long>()
        val backoff = ReconnectBackoff(
            baseDelayMillis = 1_000,
            maxDelayMillis = 30_000,
            sleep = { delays.add(it) },
            random = maxJitterRandom()
        )
        val socket = VaultEventSocket(apiClient, tokenProvider, backoff)

        // Sequence: fail, succeed-then-immediately-close (no frames), fail, succeed-with-a-frame.
        val openAttempts = AtomicInteger(0)
        val event = VaultEvent(type = "check_in", vault = null)
        socket.openSession = {
            when (openAttempts.getAndIncrement()) {
                0 -> throw IOException("connection refused")
                1 -> mockk<WebSocketSession> {
                    every { incoming } returns Channel<Frame>().apply { close() }
                    every { closeReason } returns CompletableDeferred(null)
                }
                2 -> throw IOException("connection refused")
                else -> mockk<WebSocketSession> {
                    every { incoming } returns Channel<Frame>(capacity = 1).apply {
                        trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), event)))
                    }
                    every { closeReason } returns CompletableDeferred(null)
                }
            }
        }

        val received = socket.events("vault-1").take(1).toList()

        assertEquals(listOf(event), received)
        assertEquals(4, openAttempts.get())
        // open#0 fails at attempt=0 -> delay capped at 1000ms (jittered to 999ms via
        // maxJitterRandom), attempt->1.
        // open#1 connects (then closes with no frames) -> attempt reset to 0 -> delay
        // capped at 1000ms again (not 2000ms), proving the reset, attempt->1.
        // open#2 fails at attempt=1 -> delay capped at 2000ms (jittered to 1999ms), attempt->2.
        // open#3 connects and delivers the frame; take(1) ends collection before any
        // further delay is recorded.
        assertEquals(listOf(999L, 999L, 1_999L), delays)
    }

    // =========================================================================
    // #257 — WebSocket close code 4401 handling
    // =========================================================================

    /**
     * Creates a mock [WebSocketSession] whose [incoming] channel closes immediately
     * (simulating the server closing the socket after the handshake) and whose
     * [closeReason] resolves to a [CloseReason] with the given [code].
     */
    private fun mockSessionWithClose(code: Short, emptyChannel: Channel<Frame> = Channel<Frame>().apply { close() }): WebSocketSession =
        mockk<WebSocketSession> {
            every { incoming } returns emptyChannel
            every { closeReason } returns CompletableDeferred(CloseReason(code, ""))
        }

    @Test
    fun `events on 4401 with successful refresh reconnects and emits events`() = runTest {
        val socket = VaultEventSocket(
            apiClient, tokenProvider,
            ReconnectBackoff(1_000, 30_000, sleep = {}, random = maxJitterRandom())
        )

        val refreshedToken = AuthToken(token = "new-token", expiresAt = "2099-01-01T00:00:00Z")
        socket.refreshToken = { ApiResult.Success(refreshedToken) }

        val openAttempts = AtomicInteger(0)
        val knownEvent = VaultEvent(type = "check_in", vault = null)

        socket.openSession = {
            when (openAttempts.getAndIncrement()) {
                // First connection: server immediately closes with 4401.
                0 -> mockSessionWithClose(4401)
                // Second connection (after silent refresh): delivers a real event.
                else -> mockk<WebSocketSession> {
                    every { incoming } returns Channel<Frame>(capacity = 1).apply {
                        trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), knownEvent)))
                    }
                    every { closeReason } returns CompletableDeferred(null)
                }
            }
        }

        val received = socket.events("vault-1").take(1).toList()

        assertEquals(listOf(knownEvent), received)
        assertEquals(2, openAttempts.get())
    }

    @Test
    fun `events on 4401 with failed refresh emits auth_failure and terminates`() = runTest {
        val socket = VaultEventSocket(
            apiClient, tokenProvider,
            ReconnectBackoff(1_000, 30_000, sleep = {}, random = maxJitterRandom())
        )

        // Refresh fails — token is invalid/revoked, not just expired.
        socket.refreshToken = { ApiResult.Error("Unauthorized", 401) }

        socket.openSession = {
            mockSessionWithClose(4401)
        }

        // The flow should emit exactly one auth_failure event and then terminate.
        val received = socket.events("vault-1").toList()

        assertEquals(1, received.size)
        assertEquals("auth_failure", received[0].type)
    }

    @Test
    fun `events on non-4401 close reconnects with backoff as normal`() = runTest {
        val delays = mutableListOf<Long>()
        val socket = VaultEventSocket(
            apiClient, tokenProvider,
            ReconnectBackoff(1_000, 30_000, sleep = { delays.add(it) }, random = maxJitterRandom())
        )

        // refreshToken must NOT be called for non-4401 closes.
        var refreshCalled = false
        socket.refreshToken = { refreshCalled = true; ApiResult.Error("should not be called", 0) }

        val openAttempts = AtomicInteger(0)
        val knownEvent = VaultEvent(type = "check_in", vault = null)

        socket.openSession = {
            when (openAttempts.getAndIncrement()) {
                // First connection: server closes with a normal code (e.g. 1001 Going Away).
                0 -> mockSessionWithClose(1001)
                // Second connection: delivers an event.
                else -> mockk<WebSocketSession> {
                    every { incoming } returns Channel<Frame>(capacity = 1).apply {
                        trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), knownEvent)))
                    }
                    every { closeReason } returns CompletableDeferred(null)
                }
            }
        }

        val received = socket.events("vault-1").take(1).toList()

        assertEquals(listOf(knownEvent), received)
        assertFalse("refreshToken must not be called for non-4401 closes", refreshCalled)
        // A normal close drops through to the standard backoff path.
        assertEquals(1, delays.size)
    }
}

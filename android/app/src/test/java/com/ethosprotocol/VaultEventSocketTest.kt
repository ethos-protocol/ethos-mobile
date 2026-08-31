package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.VaultEvent
import com.ethosprotocol.services.ReconnectBackoff
import com.ethosprotocol.services.ConnectionState
import com.ethosprotocol.services.VaultEventSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
                coEvery { send(any()) } returns Unit
            }
        }

        val received = socket.events("vault-1").take(1).toList()

        assertEquals(listOf(event), received)
        assertEquals(2, openAttempts.get())
        assertEquals(listOf(999L), delays)
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
                    coEvery { send(any()) } returns Unit
                }
                2 -> throw IOException("connection refused")
                else -> mockk<WebSocketSession> {
                    every { incoming } returns Channel<Frame>(capacity = 1).apply {
                        trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), event)))
                    }
                    coEvery { send(any()) } returns Unit
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

    // ── #252: Heartbeat / Ping-Pong ──────────────────────────────────────────

    @Test
    fun `events sends pong in response to server ping frame`() = runTest {
        val socket = VaultEventSocket(apiClient, tokenProvider, ReconnectBackoff.default)
        val sentFrames = mutableListOf<Frame>()
        val channel = Channel<Frame>(capacity = 2)
        val pingEvent = VaultEvent(type = "ping", vault = null)
        channel.trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), pingEvent)))

        val mockSession = mockk<WebSocketSession> {
            every { incoming } returns channel
            coEvery { send(any()) } answers { sentFrames.add(firstArg()) }
        }
        socket.openSession = { mockSession }

        // Collect the ping event
        val received = socket.events("vault-1").take(1).toList()
        assertEquals("ping", received[0].type)
        // Should have sent a pong back
        assertTrue(sentFrames.any { it is Frame.Text && (it as Frame.Text).readText().contains("pong") })
    }

    @Test
    fun `events reconnects after heartbeat send failure (silent connection death)`() = runTest {
        val delays = mutableListOf<Long>()
        val backoff = ReconnectBackoff(
            baseDelayMillis = 1_000,
            maxDelayMillis = 30_000,
            sleep = { delays.add(it) },
            random = maxJitterRandom()
        )
        val openAttempts = AtomicInteger(0)
        val socket = VaultEventSocket(apiClient, tokenProvider, backoff, heartbeatIntervalMillis = 1L)

        val channel = Channel<Frame>() // never sends any frames — simulates silent dead connection
        val hangingSession = mockk<WebSocketSession> {
            every { incoming } returns channel
            coEvery { send(any()) } throws IOException("broken pipe") // heartbeat send fails
        }
        val event = VaultEvent(type = "check_in", vault = null)
        val goodChannel = Channel<Frame>(capacity = 1)
        goodChannel.trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), event)))
        val goodSession = mockk<WebSocketSession> {
            every { incoming } returns goodChannel
            coEvery { send(any()) } returns Unit
        }
        socket.openSession = {
            when (openAttempts.getAndIncrement()) {
                0 -> hangingSession
                else -> goodSession
            }
        }

        val received = socket.events("vault-1").take(1).toList()
        assertEquals(listOf(event), received)
        assertEquals(2, openAttempts.get())
    }

    // ── #253: Multi-vault subscription ───────────────────────────────────────

    @Test
    fun `events with multiple vault IDs sends subscribe message after connect`() = runTest {
        val sentFrames = mutableListOf<Frame>()
        val socket = VaultEventSocket(apiClient, tokenProvider, ReconnectBackoff.default)

        val channel = Channel<Frame>(capacity = 1)
        val event = VaultEvent(type = "check_in", vault = null)
        channel.trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), event)))

        val mockSession = mockk<WebSocketSession> {
            every { incoming } returns channel
            coEvery { send(any()) } answers { sentFrames.add(firstArg()) }
        }
        socket.openSession = { mockSession }

        socket.events(listOf("vault-1", "vault-2", "vault-3")).take(1).toList()

        val subscribeFrame = sentFrames.filterIsInstance<Frame.Text>().find {
            it.readText().contains("subscribe")
        }
        assertNotNull(subscribeFrame)
        assertTrue(subscribeFrame!!.readText().contains("vault-2"))
        assertTrue(subscribeFrame.readText().contains("vault-3"))
    }

    @Test
    fun `events routes updates to correct vault when multiplexed`() = runTest {
        val socket = VaultEventSocket(apiClient, tokenProvider, ReconnectBackoff.default)

        val vault1 = com.ethosprotocol.models.Vault(
            id = "vault-1", owner = "owner", beneficiary = "ben", balance = 0L,
            checkInInterval = 86400L, lastCheckIn = "2026-01-01T00:00:00Z",
            status = com.ethosprotocol.models.VaultStatus.active
        )
        val vault2 = com.ethosprotocol.models.Vault(
            id = "vault-2", owner = "owner", beneficiary = "ben", balance = 0L,
            checkInInterval = 86400L, lastCheckIn = "2026-01-01T00:00:00Z",
            status = com.ethosprotocol.models.VaultStatus.active
        )
        val vault1Event = VaultEvent(type = "vault_updated", vault = vault1)
        val vault2Event = VaultEvent(type = "vault_updated", vault = vault2)

        val channel = Channel<Frame>(capacity = 2)
        channel.trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), vault1Event)))
        channel.trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), vault2Event)))

        val mockSession = mockk<WebSocketSession> {
            every { incoming } returns channel
            coEvery { send(any()) } returns Unit
        }
        socket.openSession = { mockSession }

        val received = socket.events(listOf("vault-1", "vault-2")).take(2).toList()
        assertEquals(2, received.size)
        assertEquals("vault-1", received[0].vault?.id)
        assertEquals("vault-2", received[1].vault?.id)
    }

    // ── #255: Connection Status ───────────────────────────────────────────────

    @Test
    fun `events emits CONNECTED state after successful session open`() = runTest {
        val socket = VaultEventSocket(apiClient, tokenProvider, ReconnectBackoff.default)
        val states = mutableListOf<ConnectionState>()

        val channel = Channel<Frame>(capacity = 1)
        val event = VaultEvent(type = "check_in", vault = null)
        channel.trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), event)))

        socket.openSession = {
            mockk<WebSocketSession> {
                every { incoming } returns channel
                coEvery { send(any()) } returns Unit
            }
        }

        val stateJob = launch { socket.connectionState.collect { states.add(it) } }
        socket.events("vault-1").take(1).toList()
        stateJob.cancel()

        assertTrue(states.contains(ConnectionState.CONNECTING))
        assertTrue(states.contains(ConnectionState.CONNECTED))
    }

    @Test
    fun `events emits DISCONNECTED state after connection failure`() = runTest {
        val delays = mutableListOf<Long>()
        val backoff = ReconnectBackoff(
            baseDelayMillis = 1_000, maxDelayMillis = 30_000,
            sleep = { delays.add(it) }, random = maxJitterRandom()
        )
        val socket = VaultEventSocket(apiClient, tokenProvider, backoff)
        val states = mutableListOf<ConnectionState>()

        val openAttempts = AtomicInteger(0)
        val channel = Channel<Frame>(capacity = 1)
        val event = VaultEvent(type = "check_in", vault = null)
        channel.trySend(Frame.Text(Json.encodeToString(VaultEvent.serializer(), event)))

        socket.openSession = {
            if (openAttempts.getAndIncrement() == 0) throw IOException("refused")
            mockk<WebSocketSession> {
                every { incoming } returns channel
                coEvery { send(any()) } returns Unit
            }
        }

        val stateJob = launch { socket.connectionState.collect { states.add(it) } }
        socket.events("vault-1").take(1).toList()
        stateJob.cancel()

        assertTrue(states.contains(ConnectionState.DISCONNECTED))
    }
}

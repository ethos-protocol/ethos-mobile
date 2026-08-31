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
import kotlin.random.Random
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
            mockk<WebSocketSession> { every { incoming } returns channel }
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
        // open#0 fails at attempt=0 -> delay capped at 1000ms (jittered to 999ms via
        // maxJitterRandom), attempt->1.
        // open#1 connects (then closes with no frames) -> attempt reset to 0 -> delay
        // capped at 1000ms again (not 2000ms), proving the reset, attempt->1.
        // open#2 fails at attempt=1 -> delay capped at 2000ms (jittered to 1999ms), attempt->2.
        // open#3 connects and delivers the frame; take(1) ends collection before any
        // further delay is recorded.
        assertEquals(listOf(999L, 999L, 1_999L), delays)
    }
}

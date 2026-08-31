package com.ethosprotocol

import com.ethosprotocol.api.RetryPolicy
import com.ethosprotocol.api.withRetry
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RetryPolicyTest {

    // A Random that always returns the requested upper bound minus one, i.e. the
    // largest value the [0, until) contract allows — lets tests assert an exact
    // upper-bound delay without depending on true randomness.
    private fun maxJitterRandom() = object : Random() {
        override fun nextBits(bitCount: Int): Int = Random.Default.nextBits(bitCount)
        override fun nextLong(from: Long, until: Long): Long = until - 1
    }

    @Test
    fun `withRetry jitters the delay strictly below the deterministic exponential value`() = runTest {
        val delays = mutableListOf<Long>()
        val policy = RetryPolicy(
            maxAttempts = 4,
            baseDelayMillis = 500,
            sleep = { delays.add(it) },
            random = maxJitterRandom()
        )
        var calls = 0

        withRetry(policy, isRetryable = { true }) {
            calls++
            if (calls < 4) throw IOException("boom") else "ok"
        }

        // Deterministic (pre-jitter) sequence would be 500, 1000, 2000. maxJitterRandom
        // always returns until-1, i.e. one less than the deterministic value.
        assertEquals(listOf(499L, 999L, 1_999L), delays)
        assertEquals(4, calls)
    }

    @Test
    fun `withRetry with different random sources produces different delays for the same attempt`() = runTest {
        val delaysA = mutableListOf<Long>()
        val delaysB = mutableListOf<Long>()
        val policyA = RetryPolicy(maxAttempts = 2, baseDelayMillis = 500, sleep = { delaysA.add(it) }, random = Random(1))
        val policyB = RetryPolicy(maxAttempts = 2, baseDelayMillis = 500, sleep = { delaysB.add(it) }, random = Random(2))

        runCatching { withRetry(policyA, isRetryable = { true }) { throw IOException("boom") } }
        runCatching { withRetry(policyB, isRetryable = { true }) { throw IOException("boom") } }

        assertEquals(1, delaysA.size)
        assertEquals(1, delaysB.size)
        assertNotEquals(delaysA.single(), delaysB.single())
    }

    @Test
    fun `withRetry delay never exceeds the deterministic exponential value and never goes negative`() = runTest {
        val baseDelayMillis = 500L
        val maxAttempts = 6
        for (seed in 0 until 500) {
            val delays = mutableListOf<Long>()
            val policy = RetryPolicy(
                maxAttempts = maxAttempts,
                baseDelayMillis = baseDelayMillis,
                sleep = { delays.add(it) },
                random = Random(seed.toLong())
            )

            runCatching {
                withRetry(policy, isRetryable = { true }) { throw IOException("boom") }
            }

            assertEquals(maxAttempts - 1, delays.size)
            delays.forEachIndexed { index, actual ->
                val attempt = index + 1
                val deterministicDelay = baseDelayMillis * (1L shl (attempt - 1))
                assertTrue("seed=$seed attempt=$attempt actual=$actual", actual in 0 until deterministicDelay)
            }
        }
    }
}

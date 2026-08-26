package com.ethosprotocol

import com.ethosprotocol.models.TtlCountdown
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers the local ticking countdown and its reconciliation with fresh server
 * values (#221), including the poll/push disagreement case (#223) — the
 * server-provided value must always win regardless of where the local tick
 * has drifted to.
 */
class TtlCountdownTest {

    @Test
    fun `remaining ticks down with elapsed time`() {
        val start = 1_000_000L
        val countdown = TtlCountdown(serverValue = 100, fetchedAtMillis = start)
        assertEquals(60L, countdown.remaining(nowMillis = start + 40_000))
    }

    @Test
    fun `remaining at fetch time equals server value`() {
        val start = 1_000_000L
        val countdown = TtlCountdown(serverValue = 100, fetchedAtMillis = start)
        assertEquals(100L, countdown.remaining(nowMillis = start))
    }

    @Test
    fun `remaining never goes below zero`() {
        val start = 1_000_000L
        val countdown = TtlCountdown(serverValue = 100, fetchedAtMillis = start)
        assertEquals(0L, countdown.remaining(nowMillis = start + 500_000))
    }

    @Test
    fun `reconcile replaces baseline with server value`() {
        val start = 1_000_000L
        val countdown = TtlCountdown(serverValue = 100, fetchedAtMillis = start)

        // Local tick has drifted to 60s remaining...
        val midpoint = start + 40_000
        assertEquals(60L, countdown.remaining(nowMillis = midpoint))

        // ...but a fresh server value disagrees (e.g. a check-in reset the TTL).
        val reconciled = countdown.reconcile(serverValue = 9_000, nowMillis = midpoint)

        assertEquals(9_000L, reconciled.remaining(nowMillis = midpoint))
    }

    /**
     * Simulates a poll and a `vault_updated` push disagreeing (#223): whichever
     * one delivers a fresh server value last is applied as-is — the server
     * value always wins over the other source, since both ultimately come from
     * the same server-side state.
     */
    @Test
    fun `poll push disagreement, last server value wins`() {
        val start = 1_000_000L
        var countdown = TtlCountdown(serverValue = 100, fetchedAtMillis = start)

        // A `vault_updated` push arrives first with one value...
        val pushAt = start + 10_000
        countdown = countdown.reconcile(serverValue = 500, nowMillis = pushAt)
        assertEquals(500L, countdown.remaining(nowMillis = pushAt))

        // ...then a poll response lands moments later with a different value.
        val pollAt = pushAt + 2_000
        countdown = countdown.reconcile(serverValue = 480, nowMillis = pollAt)

        assertEquals(480L, countdown.serverValue)
        assertEquals(480L, countdown.remaining(nowMillis = pollAt))
    }

    @Test
    fun `ticks down again after reconciliation`() {
        val start = 1_000_000L
        val countdown = TtlCountdown(serverValue = 100, fetchedAtMillis = start)
            .reconcile(serverValue = 200, nowMillis = start)
        assertEquals(150L, countdown.remaining(nowMillis = start + 50_000))
    }
}

package com.ethosprotocol

import com.ethosprotocol.models.WithdrawalThreshold
import com.ethosprotocol.models.isLargeWithdrawal
import org.junit.Assert.*
import org.junit.Test

/** Covers #216: the large-withdrawal confirmation threshold. */
class WithdrawalThresholdTest {

    @Test
    fun `below threshold returns false`() {
        // 70% of balance, 80% threshold.
        assertFalse(isLargeWithdrawal(7_000_000L, 10_000_000L, 8_000))
    }

    @Test
    fun `at threshold returns true`() {
        // Exactly 80% of balance, 80% threshold — inclusive.
        assertTrue(isLargeWithdrawal(8_000_000L, 10_000_000L, 8_000))
    }

    @Test
    fun `above threshold returns true`() {
        assertTrue(isLargeWithdrawal(9_500_000L, 10_000_000L, 8_000))
    }

    @Test
    fun `full balance returns true`() {
        assertTrue(isLargeWithdrawal(10_000_000L, 10_000_000L, 8_000))
    }

    @Test
    fun `zero balance returns false`() {
        assertFalse(isLargeWithdrawal(0L, 0L, 8_000))
    }

    @Test
    fun `respects configured threshold`() {
        // Same 70% amount, but a lower configured threshold now catches it —
        // proves the threshold is a parameter, not hardcoded in the check.
        assertTrue(isLargeWithdrawal(7_000_000L, 10_000_000L, 5_000))
    }

    @Test
    fun `default threshold constant is 80 percent`() {
        assertEquals(8_000, WithdrawalThreshold.LARGE_WITHDRAWAL_BPS)
    }
}

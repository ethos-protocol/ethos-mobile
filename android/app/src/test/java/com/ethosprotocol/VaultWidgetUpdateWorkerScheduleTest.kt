package com.ethosprotocol

import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #199 — the widget refresh interval scales with TTL urgency, mirroring iOS
 * TTLWidget.computeNextUpdateInterval.
 */
class VaultWidgetUpdateWorkerScheduleTest {

    @Test
    fun `determineUpdateInterval picks idle interval when ttl far away`() {
        assertEquals(60L, VaultWidgetUpdateWorker.determineUpdateInterval(172_800L))
    }

    @Test
    fun `determineUpdateInterval picks idle interval exactly at the 24h threshold`() {
        assertEquals(60L, VaultWidgetUpdateWorker.determineUpdateInterval(86_400L))
    }

    @Test
    fun `determineUpdateInterval picks normal interval just under 24h`() {
        assertEquals(15L, VaultWidgetUpdateWorker.determineUpdateInterval(86_399L))
    }

    @Test
    fun `determineUpdateInterval picks normal interval at 6h`() {
        assertEquals(15L, VaultWidgetUpdateWorker.determineUpdateInterval(21_600L))
    }

    @Test
    fun `determineUpdateInterval picks elevated interval between 1h and 6h`() {
        assertEquals(10L, VaultWidgetUpdateWorker.determineUpdateInterval(21_599L))
        assertEquals(10L, VaultWidgetUpdateWorker.determineUpdateInterval(3_600L))
    }

    @Test
    fun `determineUpdateInterval picks urgent interval between 30m and 1h`() {
        assertEquals(5L, VaultWidgetUpdateWorker.determineUpdateInterval(3_599L))
        assertEquals(5L, VaultWidgetUpdateWorker.determineUpdateInterval(1_800L))
    }

    @Test
    fun `determineUpdateInterval picks critical interval under 30m`() {
        assertEquals(2L, VaultWidgetUpdateWorker.determineUpdateInterval(1_799L))
        assertEquals(2L, VaultWidgetUpdateWorker.determineUpdateInterval(0L))
    }

    @Test
    fun `determineUpdateInterval picks idle interval when ttl unknown`() {
        assertEquals(60L, VaultWidgetUpdateWorker.determineUpdateInterval(null))
    }
}

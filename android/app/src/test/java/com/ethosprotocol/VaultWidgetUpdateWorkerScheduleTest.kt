package com.ethosprotocol

import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultWidgetUpdateWorkerScheduleTest {

    @Test
    fun `determineUpdateInterval picks urgent interval when ttl just under threshold`() {
        assertEquals(15L, VaultWidgetUpdateWorker.determineUpdateInterval(86_399L))
    }

    @Test
    fun `determineUpdateInterval picks normal interval exactly at threshold`() {
        assertEquals(60L, VaultWidgetUpdateWorker.determineUpdateInterval(86_400L))
    }

    @Test
    fun `determineUpdateInterval picks normal interval when ttl far away`() {
        assertEquals(60L, VaultWidgetUpdateWorker.determineUpdateInterval(172_800L))
    }

    @Test
    fun `determineUpdateInterval picks urgent interval when ttl very low`() {
        assertEquals(15L, VaultWidgetUpdateWorker.determineUpdateInterval(3_600L))
    }

    @Test
    fun `determineUpdateInterval picks normal interval when ttl unknown`() {
        assertEquals(60L, VaultWidgetUpdateWorker.determineUpdateInterval(null))
    }
}

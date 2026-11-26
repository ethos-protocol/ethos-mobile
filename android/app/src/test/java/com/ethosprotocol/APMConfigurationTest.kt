package com.ethosprotocol

import com.ethosprotocol.utils.APMConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for APMConfiguration (#817).
 *
 * APMConfiguration is a pure constant holder for performance alert thresholds and
 * in-memory metric retention (the sampling/retention configuration consumed by
 * PerformanceMonitor). These tests pin the configured values so an inadvertent
 * threshold change is caught at review.
 */
class APMConfigurationTest {

    @Test
    fun `API call slow and critical thresholds are configured`() {
        assertEquals(2000L, APMConfiguration.API_SLOW_THRESHOLD_MS)
        assertEquals(5000L, APMConfiguration.API_CRITICAL_THRESHOLD_MS)
    }

    @Test
    fun `screen load slow and critical thresholds are configured`() {
        assertEquals(500L, APMConfiguration.SCREEN_SLOW_THRESHOLD_MS)
        assertEquals(2000L, APMConfiguration.SCREEN_CRITICAL_THRESHOLD_MS)
    }

    @Test
    fun `startup slow threshold is configured`() {
        assertEquals(3000L, APMConfiguration.STARTUP_SLOW_THRESHOLD_MS)
    }

    @Test
    fun `in-memory metric retention counts are configured`() {
        assertEquals(100, APMConfiguration.API_METRICS_RETENTION_COUNT)
        assertEquals(50, APMConfiguration.SCREEN_METRICS_RETENTION_COUNT)
    }

    @Test
    fun `alerting is enabled by default`() {
        assertTrue(APMConfiguration.ALERTING_ENABLED)
    }
}

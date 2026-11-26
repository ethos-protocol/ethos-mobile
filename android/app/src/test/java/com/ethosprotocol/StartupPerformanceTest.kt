package com.ethosprotocol

import com.ethosprotocol.utils.StartupPerformance
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for StartupPerformance (#817).
 *
 * StartupPerformance bounds the cold-start trace with markAppStart (start) and
 * markFirstFrame (stop). These tests exercise the start/stop of that trace and
 * the elapsed-time accessors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StartupPerformanceTest {

    @Test
    fun `markAppStart starts the cold-start timer`() {
        StartupPerformance.markAppStart()
        assertTrue(StartupPerformance.getColdStartTimeMs() >= 0)
    }

    @Test
    fun `markFirstFrame records a non-negative first-frame elapsed time`() {
        StartupPerformance.markAppStart()
        StartupPerformance.markFirstFrame()
        assertTrue(StartupPerformance.getFirstFrameTimeMs() >= 0)
    }

    @Test
    fun `cold start time is not earlier than the first frame time`() {
        StartupPerformance.markAppStart()
        StartupPerformance.markFirstFrame()
        assertTrue(StartupPerformance.getColdStartTimeMs() >= StartupPerformance.getFirstFrameTimeMs())
    }
}

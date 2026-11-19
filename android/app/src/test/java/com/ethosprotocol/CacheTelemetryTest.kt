package com.ethosprotocol

import com.ethosprotocol.api.CacheTelemetry
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheTelemetryTest {

    @Before
    fun resetCounters() {
        CacheTelemetry.reset()
    }

    @Test
    fun snapshotReportsEachRecordedCounter() {
        CacheTelemetry.recordHit()
        CacheTelemetry.recordHit()
        CacheTelemetry.recordMiss()
        CacheTelemetry.recordStaleServed()
        CacheTelemetry.recordWrite(1_536L)

        val snapshot = CacheTelemetry.snapshot()

        assertEquals(2L, snapshot.hits)
        assertEquals(1L, snapshot.misses)
        assertEquals(1L, snapshot.staleServed)
        assertEquals(1_536L, snapshot.bytesWritten)
    }

    @Test
    fun negativeWriteSizesAreIgnored() {
        CacheTelemetry.recordWrite(-100L)

        assertEquals(0L, CacheTelemetry.snapshot().bytesWritten)
    }

    @Test
    fun resetClearsAllCounters() {
        CacheTelemetry.recordHit()
        CacheTelemetry.recordWrite(10L)

        CacheTelemetry.reset()

        val snapshot = CacheTelemetry.snapshot()
        assertEquals(0L, snapshot.hits)
        assertEquals(0L, snapshot.bytesWritten)
    }
}

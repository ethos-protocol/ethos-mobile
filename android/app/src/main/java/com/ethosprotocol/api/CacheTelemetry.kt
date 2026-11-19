package com.ethosprotocol.api

/** Tracks offline-cache access statistics for debug/support diagnostics. */
object CacheTelemetry {
    private val _hits = java.util.concurrent.atomic.AtomicLong(0)
    private val _misses = java.util.concurrent.atomic.AtomicLong(0)
    private val _staleServed = java.util.concurrent.atomic.AtomicLong(0)
    private val _bytesWritten = java.util.concurrent.atomic.AtomicLong(0)

    val hits: Long get() = _hits.get()
    val misses: Long get() = _misses.get()
    val staleServed: Long get() = _staleServed.get()
    val bytesWritten: Long get() = _bytesWritten.get()

    fun recordHit() { _hits.incrementAndGet() }
    fun recordMiss() { _misses.incrementAndGet() }
    fun recordStaleServed() { _staleServed.incrementAndGet() }
    fun recordWrite(bytes: Long) { _bytesWritten.addAndGet(bytes.coerceAtLeast(0L)) }

    fun reset() { _hits.set(0); _misses.set(0); _staleServed.set(0); _bytesWritten.set(0) }

    fun snapshot() = CacheTelemetrySnapshot(hits, misses, staleServed, bytesWritten)
}

data class CacheTelemetrySnapshot(val hits: Long, val misses: Long, val staleServed: Long, val bytesWritten: Long)

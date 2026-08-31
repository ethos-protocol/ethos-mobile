package com.ethosprotocol

import android.content.Context
import com.ethosprotocol.api.OfflineCache
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OfflineCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newCache(): OfflineCache {
        val context: Context = mockk()
        every { context.cacheDir } returns tempFolder.root
        return OfflineCache(context)
    }

    @Test
    fun `save then load returns the original payload`() {
        val cache = newCache()

        cache.save("/vaults", "{\"a\":1}")
        val loaded = cache.load("/vaults")

        assertEquals("{\"a\":1}", loaded?.data)
    }

    @Test
    fun `load returns null for a key that was never saved`() {
        assertNull(newCache().load("/missing"))
    }

    @Test
    fun `save records a timestamp close to the write time`() {
        val cache = newCache()

        val before = System.currentTimeMillis()
        cache.save("/vaults", "{}")
        val after = System.currentTimeMillis()
        val loaded = cache.load("/vaults")!!

        assertTrue(loaded.timestamp in before..after)
    }

    @Test
    fun `distinct keys are cached independently`() {
        val cache = newCache()

        cache.save("/vaults", "{\"list\":[]}")
        cache.save("/vaults/v1", "{\"id\":\"v1\"}")

        assertEquals("{\"list\":[]}", cache.load("/vaults")?.data)
        assertEquals("{\"id\":\"v1\"}", cache.load("/vaults/v1")?.data)
    }

    @Test
    fun `evicts the least recently used entry once the byte cap is exceeded`() {
        val payload = "x".repeat(100)
        // Each envelope on disk is ~137 bytes (13-digit timestamp + JSON overhead + payload).
        // 320 comfortably fits two entries (~274 bytes) but not three (~411 bytes), so eviction
        // is only forced by the third save below.
        val cache = newCache().apply { maxCacheBytes = 320 }

        cache.save("/a", payload)
        cache.save("/b", payload)
        cache.load("/a") // touch "a" so "b" becomes the least recently used
        cache.save("/c", payload) // should evict "b", not "a"

        assertNotNull("most-recently-used entry should survive eviction", cache.load("/a"))
        assertNull("least-recently-used entry should be evicted", cache.load("/b"))
        assertNotNull("newest entry should survive eviction", cache.load("/c"))
    }

    @Test
    fun `never evicts while under the byte cap`() {
        val cache = newCache().apply { maxCacheBytes = OfflineCache.DEFAULT_MAX_CACHE_BYTES }

        cache.save("/a", "small")
        cache.save("/b", "small")
        cache.save("/c", "small")

        assertNotNull(cache.load("/a"))
        assertNotNull(cache.load("/b"))
        assertNotNull(cache.load("/c"))
    }

    @Test
    fun `clear removes every cached entry`() {
        val cache = newCache()
        cache.save("/a", "{}")
        cache.save("/b", "{}")

        cache.clear()

        assertNull(cache.load("/a"))
        assertNull(cache.load("/b"))
    }

    @Test
    fun `concurrent reads and writes do not corrupt cache entries`() {
        val cache = newCache()
        val iterations = 50
        val latch = CountDownLatch(2)
        var writeException: Throwable? = null
        var readException: Throwable? = null

        val writer = Thread {
            try {
                repeat(iterations) { i -> cache.save("/vaults", "{\"i\":$i}") }
            } catch (e: Throwable) { writeException = e }
            finally { latch.countDown() }
        }
        val reader = Thread {
            try {
                repeat(iterations) { cache.load("/vaults") }
            } catch (e: Throwable) { readException = e }
            finally { latch.countDown() }
        }

        writer.start(); reader.start()
        assertTrue("threads did not finish in time", latch.await(10, TimeUnit.SECONDS))
        assertNull("writer threw: $writeException", writeException)
        assertNull("reader threw: $readException", readException)
        // Final state must be a valid cache entry (not a torn write)
        val result = cache.load("/vaults")
        assertNotNull("cache should have an entry after concurrent writes", result)
    }
}

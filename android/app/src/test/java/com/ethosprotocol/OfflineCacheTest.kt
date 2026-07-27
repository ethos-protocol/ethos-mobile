package com.ethosprotocol

import android.content.Context
import com.ethosprotocol.api.OfflineCache
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}

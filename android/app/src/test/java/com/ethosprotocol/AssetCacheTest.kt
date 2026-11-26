package com.ethosprotocol

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.utils.AssetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unit tests for AssetCache (#818).
 *
 * AssetCache caches status colors (resolved from the material theme) and exposes
 * warning/offline icons. Covers cache hits, clearCache(), and concurrent access —
 * the surface item 89 flagged for a concurrency risk.
 */
@RunWith(RobolectricTestRunner::class)
class AssetCacheTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val scheme = lightColorScheme(
        primary = Color.Red,
        error = Color.Green,
        secondary = Color.Blue,
        tertiary = Color.Magenta
    )

    @Before
    fun setUp() {
        AssetCache.clearCache()
    }

    @Test
    fun `getStatusColor resolves each vault status to its theme color`() {
        val colors = mutableMapOf<VaultStatus, Color>()
        composeTestRule.setContent {
            MaterialTheme(colorScheme = scheme) {
                colors[VaultStatus.active] = AssetCache.getStatusColor(VaultStatus.active)
                colors[VaultStatus.expired] = AssetCache.getStatusColor(VaultStatus.expired)
                colors[VaultStatus.released] = AssetCache.getStatusColor(VaultStatus.released)
                colors[VaultStatus.paused] = AssetCache.getStatusColor(VaultStatus.paused)
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(scheme.primary, colors[VaultStatus.active])
        assertEquals(scheme.error, colors[VaultStatus.expired])
        assertEquals(scheme.secondary, colors[VaultStatus.released])
        assertEquals(scheme.tertiary, colors[VaultStatus.paused])
    }

    @Test
    fun `getStatusColor caches the resolved color so repeated calls do not re-resolve`() {
        composeTestRule.setContent {
            MaterialTheme(colorScheme = scheme) {
                AssetCache.getStatusColor(VaultStatus.active)
                AssetCache.getStatusColor(VaultStatus.active)
            }
        }
        composeTestRule.waitForIdle()
        val cache = colorCache()
        assertEquals("two calls for one status must produce a single cached entry", 1, cache.size)
        assertTrue("the active status color must be cached", cache.containsKey(VaultStatus.active))
    }

    @Test
    fun `clearCache empties the status color and icon caches`() {
        composeTestRule.setContent {
            MaterialTheme(colorScheme = scheme) {
                AssetCache.getStatusColor(VaultStatus.active)
            }
        }
        composeTestRule.waitForIdle()
        assertTrue("cache must hold the resolved color before clear", colorCache().containsKey(VaultStatus.active))

        AssetCache.clearCache()

        assertTrue("clearCache must empty the status color cache", colorCache().isEmpty())
        assertTrue("clearCache must empty the status icon cache", iconCache().isEmpty())
    }

    @Test
    fun `concurrent access to icon getters and clearCache does not throw`() {
        // Populate the status color cache via a composition and prime the icon singletons so
        // the worker threads exercise clearCache against a populated cache and read stable icons.
        composeTestRule.setContent {
            MaterialTheme(colorScheme = scheme) {
                AssetCache.getStatusColor(VaultStatus.active)
                AssetCache.getStatusColor(VaultStatus.expired)
                AssetCache.getStatusColor(VaultStatus.released)
                AssetCache.getStatusColor(VaultStatus.paused)
            }
        }
        composeTestRule.waitForIdle()
        assertTrue(colorCache().isNotEmpty())
        AssetCache.getWarningIcon()
        AssetCache.getOfflineIcon()

        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(8) {
                executor.submit {
                    try {
                        repeat(200) {
                            AssetCache.getWarningIcon()
                            AssetCache.getOfflineIcon()
                            AssetCache.clearCache()
                        }
                    } catch (t: Throwable) {
                        errors += t
                    }
                }
            }
        } finally {
            executor.shutdown()
        }
        assertTrue("concurrent access must not throw: $errors", executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue("concurrent access must not throw: $errors", errors.isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun colorCache(): Map<Any?, Any?> =
        AssetCache::class.java.getDeclaredField("statusColorCache").apply { isAccessible = true }
            .get(AssetCache) as Map<Any?, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun iconCache(): Map<Any?, Any?> =
        AssetCache::class.java.getDeclaredField("statusIconCache").apply { isAccessible = true }
            .get(AssetCache) as Map<Any?, Any?>
}

package com.ethosprotocol.testing

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Utility functions for testing RTL layout support.
 *
 * These utilities help enable and verify RTL layout in both manual and automated testing scenarios.
 *
 * Note: For automated Paparazzi snapshot testing, use manual verification with:
 * ```
 * adb shell settings put global debug.force_rtl_layout 1
 * ./gradlew verifyPaparazziDebug
 * adb shell settings put global debug.force_rtl_layout 0
 * ```
 */
object RTLTestUtils {

    /**
     * Check if the device is currently configured for RTL layout.
     *
     * Returns true if:
     * 1. The system has forced RTL layout enabled via developer settings
     * 2. The device locale is an RTL locale (e.g., Arabic, Hebrew)
     */
    fun isRTLEnabled(context: Context): Boolean {
        val isLocaleRTL = isRTLLocale(Locale.getDefault())
        val config = context.resources.configuration
        val isLayoutRTL = config.layoutDirection == Configuration.LAYOUT_DIRECTION_RTL
        return isLocaleRTL || isLayoutRTL
    }

    /**
     * Determine if a locale is RTL-based.
     *
     * Common RTL locales:
     * - Arabic (ar)
     * - Hebrew (he)
     * - Persian (fa)
     * - Urdu (ur)
     */
    fun isRTLLocale(locale: Locale): Boolean {
        val language = locale.language.lowercase()
        return language in setOf("ar", "he", "fa", "ur", "ji", "yi", "iw")
    }

    /**
     * Get a list of common RTL locales for testing.
     *
     * Useful for parameterized tests that verify RTL support across multiple locales.
     */
    fun getCommonRTLLocales(): List<Locale> = listOf(
        Locale("ar"), // Arabic
        Locale("he"), // Hebrew
        Locale("fa"), // Persian/Farsi
        Locale("ur")  // Urdu
    )
}

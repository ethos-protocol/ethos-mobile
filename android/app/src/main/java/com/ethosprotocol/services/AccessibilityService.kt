package com.ethosprotocol.services

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object AccessibilityService {
    /**
     * Checks if reduce-motion/animation is enabled in the device's accessibility settings.
     * On Android, this can be checked via:
     * 1. animator_duration_scale (0 = animations disabled, 1 = normal, >1 = slow)
     * 2. Accessibility settings for animation reduction
     *
     * Returns true if animations should be disabled or minimized.
     */
    fun isReduceMotionEnabled(context: Context): Boolean {
        return try {
            val animatorDurationScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            // If animator_duration_scale is 0, animations are disabled
            animatorDurationScale == 0f
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if the device has accessibility services enabled that might suggest
     * the user prefers reduced motion/animations.
     */
    fun hasAccessibilityServiceEnabled(context: Context): Boolean {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return accessibilityManager?.isEnabled == true
    }
}

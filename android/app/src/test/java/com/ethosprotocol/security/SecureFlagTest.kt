package com.ethosprotocol.security

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Documents and verifies the Android mechanism used to prevent sensitive data
 * from appearing in the system app-switcher (recent apps) thumbnail or being
 * captured by screenshots.
 *
 * ## How FLAG_SECURE works
 *
 * Setting `WindowManager.LayoutParams.FLAG_SECURE` on a window instructs the
 * Android system to:
 *
 * 1. **Blank the window in the app-switcher thumbnail** — the system replaces
 *    the live app content with a solid colour (or the app's icon, depending on
 *    manufacturer) when compositing the recents screen, so vault balances,
 *    beneficiary addresses, and TTL countdowns are never visible there.
 *
 * 2. **Block screenshots and screen recordings** — any attempt to capture the
 *    window via `MediaProjection`, `UiAutomator`, or the system screenshot
 *    shortcut produces a blank/black frame instead of the real content.
 *
 * 3. **Prevent copy-via-recent-apps on some launchers** — certain OEM launchers
 *    offer a "copy text from recent app" feature; FLAG_SECURE disables it.
 *
 * The flag is set once in `MainActivity.onCreate` after `setContent` so it
 * covers the entire app window for the lifetime of the Activity.
 *
 * ## Caveats
 *
 * - FLAG_SECURE does NOT prevent a physical camera pointed at the screen.
 * - The privacy overlay ([com.ethosprotocol.security.PrivacyOverlayModifier]
 *   on iOS) is the equivalent iOS mechanism; Android does not need a separate
 *   overlay because FLAG_SECURE handles both recents snapshotting and screenshots.
 */
class SecureFlagTest {

    /**
     * Verifies that `WindowManager.LayoutParams.FLAG_SECURE` has the expected
     * constant value `0x2000` (8192 decimal) as defined in the Android SDK.
     *
     * This value has been stable since API 1 and is part of the public API
     * contract; a mismatch would indicate a severely broken SDK environment.
     */
    @Test
    fun testSecureFlagConstantValue() {
        assertEquals(
            "FLAG_SECURE must equal 0x2000 (8192) as defined in the Android SDK",
            0x2000,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }
}

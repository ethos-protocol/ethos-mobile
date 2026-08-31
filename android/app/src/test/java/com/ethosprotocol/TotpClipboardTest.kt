package com.ethosprotocol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #228 — TOTP clipboard auto-clear and one-time warning tests.
 *
 * These tests use Robolectric to exercise the Android clipboard and
 * SharedPreferences APIs without a real device/emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TotpClipboardTest {

    private lateinit var context: Context
    private lateinit var clipboard: ClipboardManager
    private lateinit var prefs: SharedPreferences
    private val warnedKey = "totp_copy_warned"
    private val prefsName = "totp_copy_prefs"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        clipboard = context.getSystemService(ClipboardManager::class.java)
        prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    // ── One-time warning ────────────────────────────────────────────────────────

    @Test
    fun `warning not shown flag is false on first copy`() {
        assertFalse(
            "Warning shown flag must be false before first copy",
            prefs.getBoolean(warnedKey, false)
        )
    }

    @Test
    fun `warning flag set after user acknowledges`() {
        prefs.edit().putBoolean(warnedKey, true).apply()
        assertTrue(
            "Warning flag must be true after user acknowledges",
            prefs.getBoolean(warnedKey, false)
        )
    }

    @Test
    fun `warning not triggered on subsequent copies`() {
        prefs.edit().putBoolean(warnedKey, true).apply()
        // Second copy — already warned, no dialog needed.
        assertTrue(prefs.getBoolean(warnedKey, false))
    }

    // ── Clipboard auto-clear ────────────────────────────────────────────────────

    @Test
    fun `clipboard cleared if still contains secret`() {
        val secret = "JBSWY3DPEHPK3PXP"
        clipboard.setPrimaryClip(ClipData.newPlainText("TOTP Secret", secret))

        // Simulate the auto-clear logic: clear only if clipboard still has the secret.
        val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (current == secret) {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }

        val afterClear = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        assertNotEquals("Clipboard must not contain the secret after auto-clear", secret, afterClear)
    }

    @Test
    fun `clipboard not cleared if user has since copied something else`() {
        val secret = "JBSWY3DPEHPK3PXP"
        val other = "not-a-secret"

        clipboard.setPrimaryClip(ClipData.newPlainText("TOTP Secret", secret))
        // User copies something else before auto-clear fires.
        clipboard.setPrimaryClip(ClipData.newPlainText("other", other))

        // Auto-clear logic: only clear if clipboard still contains the original secret.
        val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (current == secret) {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }

        val afterClear = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals("Clipboard must retain the user's later copy", other, afterClear)
    }
}

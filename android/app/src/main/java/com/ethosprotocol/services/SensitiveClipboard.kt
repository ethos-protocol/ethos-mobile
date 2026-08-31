package com.ethosprotocol.services

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * #270 — Centralized utility for copying sensitive values to the clipboard with
 * an automatic-clear timer.
 *
 * Any secret the user is allowed to copy — TOTP secrets, vault IDs, provisioning
 * URIs — must go through this utility so the auto-clear policy is applied
 * consistently rather than per-screen. The default delay is 60 seconds, matching
 * iOS's own password-auto-fill clipboard retention: long enough to paste into an
 * authenticator app, short enough to limit exposure if the user forgets to clear it.
 *
 * Usage (in a Composable):
 * ```kotlin
 * val context = LocalContext.current
 * SensitiveClipboard.copy(context, secret, label = "TOTP secret")
 * ```
 */
object SensitiveClipboard {

    /** Seconds the sensitive value remains on the clipboard before auto-clear. */
    const val CLEAR_DELAY_SECONDS: Long = 60L

    private val scope = CoroutineScope(Dispatchers.Main)
    private var clearJob: Job? = null

    /**
     * Copies [value] to the system clipboard under the given [label] and
     * schedules an automatic clear after [CLEAR_DELAY_SECONDS] seconds.
     * A subsequent call before the timer fires cancels the previous timer and
     * restarts it for the new value.
     */
    fun copy(context: Context, value: String, label: String = "Sensitive data") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        clipboard.setPrimaryClip(clip)

        // Cancel any in-flight clear and restart the timer.
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(CLEAR_DELAY_SECONDS * 1_000L)
            // Clear by overwriting with an empty string — the label indicates
            // this was intentionally cleared so clipboard history tools can
            // optionally suppress it.
            val clearClip = ClipData.newPlainText("Cleared", "")
            clipboard.setPrimaryClip(clearClip)
        }
    }
}

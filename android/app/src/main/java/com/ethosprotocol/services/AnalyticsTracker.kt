package com.ethosprotocol.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy-respecting event batching (#725). Events are recorded only while the user has opted
 * in, buffered in memory, and drained in batches so callers can dispatch them together.
 * Event names must not carry balances, beneficiaries or vault IDs.
 */
@Singleton
class AnalyticsTracker @Inject constructor(@ApplicationContext context: Context) {

    private companion object {
        const val PREFS_NAME = "analytics_prefs"
        const val KEY_OPT_IN = "opt_in"
        const val BATCH_SIZE = 20
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val buffer = ArrayDeque<String>()

    var isOptedIn: Boolean
        get() = prefs.getBoolean(KEY_OPT_IN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OPT_IN, value).apply()
            if (!value) synchronized(buffer) { buffer.clear() }
        }

    /** Records [eventName] if the user opted in. Returns true when the batch is ready to drain. */
    fun track(eventName: String): Boolean {
        if (!isOptedIn) return false
        return synchronized(buffer) {
            buffer.addLast(eventName)
            buffer.size >= BATCH_SIZE
        }
    }

    /** Removes and returns all buffered events, oldest first. */
    fun drain(): List<String> = synchronized(buffer) {
        val events = buffer.toList()
        buffer.clear()
        events
    }
}

package com.ethosprotocol.services

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DoNotDisturbHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    companion object {
        // Allow critical notifications (vaults expiring today) during DND
        const val VAULT_EXPIRING_TODAY_TTL_SECONDS = 86_400L
    }

    /**
     * Returns true if the device is currently in Do Not Disturb (DND) mode.
     */
    fun isInDoNotDisturb(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } else {
            false
        }
    }

    /**
     * Determines if a notification should be delayed due to DND settings.
     * Returns true if the notification should be delayed, false if it should be sent immediately.
     */
    fun shouldDelayNotification(isCritical: Boolean): Boolean {
        if (!isInDoNotDisturb()) return false
        // Critical notifications bypass DND delay
        return !isCritical
    }

    /**
     * Determines if a vault expiration notification is critical (expires in less than 24 hours).
     */
    fun isExpirationCritical(ttlRemainingSeconds: Long): Boolean {
        return ttlRemainingSeconds > 0 && ttlRemainingSeconds < VAULT_EXPIRING_TODAY_TTL_SECONDS
    }

    /**
     * Gets the appropriate delay time in milliseconds for a non-critical notification during DND.
     * Returns 0 if notification should be sent immediately.
     */
    fun getDelayForNotification(isCritical: Boolean): Long {
        if (!shouldDelayNotification(isCritical)) return 0
        // Delay non-critical notifications until DND ends or by a reasonable period
        // For now, return 30 minutes (1800000ms) as a reasonable delay
        return 30 * 60 * 1000L
    }
}

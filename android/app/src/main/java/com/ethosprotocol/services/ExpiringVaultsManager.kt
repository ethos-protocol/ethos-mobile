package com.ethosprotocol.services

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ethosprotocol.models.Vault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class ExpiringVaultsBannerState(
    val expiredVaults: List<Vault> = emptyList(),
    val isDismissed: Boolean = false,
    val dismissedUntil: Long = 0L // Timestamp when dismissal expires
)

@Singleton
class ExpiringVaultsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("expiring_vaults", Context.MODE_PRIVATE)

    private val _bannerState = MutableStateFlow(ExpiringVaultsBannerState())
    val bannerState: StateFlow<ExpiringVaultsBannerState> = _bannerState.asStateFlow()

    companion object {
        const val EXPIRING_NOTIFICATION_ID = 9_002
        const val DISMISS_DURATION_MS = 60 * 60 * 1000L // 1 hour
        private const val DISMISSED_UNTIL_PREF = "banner_dismissed_until"
    }

    fun updateVaults(vaults: List<Vault>) {
        val expiringVaults = vaults.filter { it.isExpiringSoon }
        val dismissedUntil = prefs.getLong(DISMISSED_UNTIL_PREF, 0L)
        val isDismissed = dismissedUntil > System.currentTimeMillis()

        _bannerState.update {
            it.copy(
                expiredVaults = expiringVaults,
                isDismissed = isDismissed,
                dismissedUntil = dismissedUntil
            )
        }

        if (expiringVaults.isNotEmpty() && !isDismissed) {
            showExpiringVaultsNotification(expiringVaults)
        }
    }

    fun dismissBanner() {
        val dismissUntil = System.currentTimeMillis() + DISMISS_DURATION_MS
        prefs.edit().putLong(DISMISSED_UNTIL_PREF, dismissUntil).apply()
        _bannerState.update {
            it.copy(isDismissed = true, dismissedUntil = dismissUntil)
        }
        cancelNotification()
    }

    fun resetDismissal() {
        prefs.edit().remove(DISMISSED_UNTIL_PREF).apply()
        _bannerState.update {
            it.copy(isDismissed = false, dismissedUntil = 0L)
        }
        val currentState = _bannerState.value
        if (currentState.expiredVaults.isNotEmpty()) {
            showExpiringVaultsNotification(currentState.expiredVaults)
        }
    }

    private fun showExpiringVaultsNotification(vaults: List<Vault>) {
        if (vaults.isEmpty()) return

        val summary = if (vaults.size == 1) {
            "1 vault expires in less than 24 hours"
        } else {
            "${vaults.size} vaults expire in less than 24 hours"
        }

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Vaults Expiring Soon")
            .setContentText(summary)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .build()

        NotificationManagerCompat.from(context).notify(EXPIRING_NOTIFICATION_ID, notification)
    }

    private fun cancelNotification() {
        NotificationManagerCompat.from(context).cancel(EXPIRING_NOTIFICATION_ID)
    }
}

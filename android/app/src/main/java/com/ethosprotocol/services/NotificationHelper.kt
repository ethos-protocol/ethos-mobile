package com.ethosprotocol.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ethosprotocol.services.PendingActionType
import com.ethosprotocol.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        const val CHANNEL_ID = "ttl_reminders"
        const val CHANNEL_NAME = "Check-in Reminders"
        const val QUEUED_CHANNEL_ID = "ttl_queued"
        const val QUEUED_CHANNEL_NAME = "Queued Requests"
        const val QUEUED_NOTIFICATION_ID = 9_001
        const val EXPIRED_CHANNEL_ID = "vault_expired"
        const val EXPIRED_CHANNEL_NAME = "Vault Expiry Alerts"

        // Reserved range for per-vault notification IDs, kept clear of QUEUED_NOTIFICATION_ID
        // and NO_VAULT_NOTIFICATION_ID below.
        const val VAULT_NOTIFICATION_ID_RANGE_START = 10_000
        // Fallback ID for the (practically unused) case where show() is called without a
        // vaultId — sits below the reserved vault range so it can never collide with it.
        const val NO_VAULT_NOTIFICATION_ID = 1

        private const val VAULT_NOTIFICATION_IDS_PREFS = "vault_notification_ids"
    }

    // String.hashCode() collides between distinct vault IDs within the 32-bit hash space, which
    // would make one vault's notification silently replace another's. Instead, persist a stable
    // assignment of each vault ID to the next free slot in a reserved ID range — two distinct
    // vault IDs are then guaranteed distinct notification IDs for as long as the mapping lives,
    // rather than merely "unlikely" to collide.
    private val vaultNotificationIdPrefs =
        context.getSharedPreferences(VAULT_NOTIFICATION_IDS_PREFS, Context.MODE_PRIVATE)

    init {
        createChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        createChannel(QUEUED_CHANNEL_ID, QUEUED_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        createChannel(EXPIRED_CHANNEL_ID, EXPIRED_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
    }

    @Synchronized
    fun notificationIdFor(vaultId: String?): Int {
        if (vaultId == null) return NO_VAULT_NOTIFICATION_ID
        vaultNotificationIdPrefs.getInt(vaultId, -1).takeIf { it != -1 }?.let { return it }
        val id = VAULT_NOTIFICATION_ID_RANGE_START + vaultNotificationIdPrefs.all.size
        vaultNotificationIdPrefs.edit().putInt(vaultId, id).apply()
        return id
    }

    fun show(title: String, body: String, vaultId: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            vaultId?.let { data = android.net.Uri.parse("ethosprotocol://vault/$it/check-in") }
        }
        val pi = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Groups all of a vault's notifications together so they visually cluster even if
            // notificationIdFor() were ever wrong, rather than relying solely on ID uniqueness
            // for replace-vs-append behavior.
            .setGroup(vaultId ?: "general")
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(notificationIdFor(vaultId), notification)
    }

    fun showQueuedActions(count: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(context, QUEUED_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val body = if (count == 1) "1 request will be submitted when back online"
                   else "$count requests will be submitted when back online"

        val notification = NotificationCompat.Builder(context, QUEUED_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Request queued")
            .setContentText(body)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(QUEUED_NOTIFICATION_ID, notification)
    }

    fun cancelQueuedActions() {
        context.getSystemService(NotificationManager::class.java).cancel(QUEUED_NOTIFICATION_ID)
    }

    fun showVaultExpiredNotification(vaultId: String, actionType: PendingActionType) {
        val actionLabel = if (actionType == PendingActionType.CHECK_IN) "check-in" else "request"
        val body = "A queued $actionLabel was discarded because this vault already expired " +
            "while you were offline. The vault may have released funds to the beneficiary."
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (vaultId.isNotEmpty())
                data = android.net.Uri.parse("ethosprotocol://vault/$vaultId/view-details")
        }
        val pi = PendingIntent.getActivity(
            context, vaultId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, EXPIRED_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Check-in Failed \u2014 Vault Expired")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationIdFor(vaultId.ifEmpty { null }), notification)
    }

    private fun createChannel(id: String, name: String, importance: Int) {
        val channel = NotificationChannel(id, name, importance)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

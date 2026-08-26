package com.ethosprotocol.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ethosprotocol.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
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

        // Reserved range for per-vault notification IDs, kept clear of QUEUED_NOTIFICATION_ID
        // and NO_VAULT_NOTIFICATION_ID below.
        const val VAULT_NOTIFICATION_ID_RANGE_START = 10_000
        // Fallback ID for the (practically unused) case where show() is called without a
        // vaultId — sits below the reserved vault range so it can never collide with it.
        const val NO_VAULT_NOTIFICATION_ID = 1

        private const val VAULT_NOTIFICATION_IDS_PREFS = "vault_notification_ids"

        // --- Check-in reminder lead-time scaling (ported from iOS
        // NotificationService.scheduleCheckInReminder, #197) ---

        /** The primary reminder fires one tenth of the check-in interval before expiry… */
        private const val PRIMARY_LEAD_TIME_DIVISOR = 10L
        /** …capped at 24 hours, so long-TTL vaults are not warned days in advance. */
        private const val MAX_PRIMARY_LEAD_TIME_SECONDS = 86_400L
        /** Vaults with a check-in interval under 24h also get an urgent reminder 2h before expiry. */
        private const val SHORT_INTERVAL_THRESHOLD_SECONDS = 86_400L
        private const val SECONDARY_LEAD_TIME_SECONDS = 7_200L
        /** Never schedule in the past — mirror iOS's 60-second floor. */
        private const val MIN_REMINDER_DELAY_SECONDS = 60L

        internal const val REMINDER_WORK_PREFIX_PRIMARY = "checkin-primary-"
        internal const val REMINDER_WORK_PREFIX_SECONDARY = "checkin-secondary-"

        /** Lead time, in seconds, between the primary reminder and expiry. */
        internal fun primaryLeadTimeSeconds(checkInInterval: Long): Long =
            (checkInInterval / PRIMARY_LEAD_TIME_DIVISOR)
                .coerceIn(0L, MAX_PRIMARY_LEAD_TIME_SECONDS)

        /** Delay, in seconds, before the primary reminder fires. */
        internal fun primaryReminderDelaySeconds(ttlRemaining: Long, checkInInterval: Long): Long =
            (ttlRemaining - primaryLeadTimeSeconds(checkInInterval))
                .coerceAtLeast(MIN_REMINDER_DELAY_SECONDS)

        /** Delay, in seconds, before the urgent short-interval reminder fires. */
        internal fun secondaryReminderDelaySeconds(ttlRemaining: Long): Long =
            (ttlRemaining - SECONDARY_LEAD_TIME_SECONDS)
                .coerceAtLeast(MIN_REMINDER_DELAY_SECONDS)

        /**
         * True when a second, more urgent reminder is worth scheduling: only for short check-in
         * intervals, and only when it would actually land after the primary reminder.
         */
        internal fun hasSecondaryReminder(ttlRemaining: Long, checkInInterval: Long): Boolean =
            checkInInterval < SHORT_INTERVAL_THRESHOLD_SECONDS &&
                secondaryReminderDelaySeconds(ttlRemaining) >
                primaryReminderDelaySeconds(ttlRemaining, checkInInterval)
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

    /**
     * (Re)schedules the local check-in reminders for [vaultId], scaling the lead time to the
     * vault's check-in interval instead of sending one generic reminder (#197).
     *
     * Both reminders are unique work keyed by vault ID and enqueued with
     * [ExistingWorkPolicy.REPLACE], so calling this again after a check-in (or any other TTL
     * change) simply re-times the pending reminders.
     */
    fun scheduleCheckInReminder(vaultId: String, ttlRemaining: Long?, checkInInterval: Long) {
        val workManager = WorkManager.getInstance(context)
        val primaryWork = REMINDER_WORK_PREFIX_PRIMARY + vaultId
        val secondaryWork = REMINDER_WORK_PREFIX_SECONDARY + vaultId

        if (ttlRemaining == null || ttlRemaining <= 0L) {
            workManager.cancelUniqueWork(primaryWork)
            workManager.cancelUniqueWork(secondaryWork)
            return
        }

        workManager.enqueueUniqueWork(
            primaryWork,
            ExistingWorkPolicy.REPLACE,
            reminderRequest(
                vaultId = vaultId,
                title = "Check-in Reminder",
                body = "Your vault expires soon. Tap to check in and keep it active.",
                delaySeconds = primaryReminderDelaySeconds(ttlRemaining, checkInInterval)
            )
        )

        if (hasSecondaryReminder(ttlRemaining, checkInInterval)) {
            workManager.enqueueUniqueWork(
                secondaryWork,
                ExistingWorkPolicy.REPLACE,
                reminderRequest(
                    vaultId = vaultId,
                    title = "Check-in Urgent",
                    body = "Your vault expires in about 2 hours. Check in now to prevent loss of access.",
                    delaySeconds = secondaryReminderDelaySeconds(ttlRemaining)
                )
            )
        } else {
            workManager.cancelUniqueWork(secondaryWork)
        }
    }

    /** Cancels any pending check-in reminders for [vaultId]. */
    fun cancelCheckInReminders(vaultId: String) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(REMINDER_WORK_PREFIX_PRIMARY + vaultId)
        workManager.cancelUniqueWork(REMINDER_WORK_PREFIX_SECONDARY + vaultId)
    }

    private fun reminderRequest(vaultId: String, title: String, body: String, delaySeconds: Long) =
        OneTimeWorkRequestBuilder<CheckInReminderWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    CheckInReminderWorker.KEY_VAULT_ID to vaultId,
                    CheckInReminderWorker.KEY_TITLE to title,
                    CheckInReminderWorker.KEY_BODY to body
                )
            )
            .build()

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

    private fun createChannel(id: String, name: String, importance: Int) {
        val channel = NotificationChannel(id, name, importance)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

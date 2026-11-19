package com.ethosprotocol.services

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** One background work item whose wake-up cost is tracked against a battery budget. */
data class BatteryTaskMetric(
    val name: String,
    val purpose: String,
    val intervalMinutes: Long,
    val requiresNetwork: Boolean,
    val maxWakeupsPerHour: Double
)

/**
 * Battery-drain budgets for background work (#450). Each power-hungry operation declares
 * its interval and the maximum wake-ups per hour it may cause; [isWithinBudget] is the
 * check the unit suite asserts against, so a regression in an interval fails the test.
 */
object BatteryDrainMetrics {
    const val MAX_URGENT_WAKEUPS_PER_HOUR = 4.0
    const val MAX_NORMAL_WAKEUPS_PER_HOUR = 1.0

    /**
     * Work items that are most likely to impact battery life because they wake the device,
     * fetch remote data, or run while the app is backgrounded.
     */
    val powerHungryOperations = listOf(
        BatteryTaskMetric(
            name = "VaultWidgetUpdateWorker",
            purpose = "Refresh cached widget data and TTL status",
            intervalMinutes = 15L,
            requiresNetwork = true,
            maxWakeupsPerHour = MAX_URGENT_WAKEUPS_PER_HOUR
        ),
        BatteryTaskMetric(
            name = "PendingActionSyncWorker",
            purpose = "Retry queued offline actions once connectivity returns",
            intervalMinutes = 15L,
            requiresNetwork = true,
            maxWakeupsPerHour = MAX_URGENT_WAKEUPS_PER_HOUR
        )
    )

    fun estimateWakeupsPerHour(intervalMinutes: Long): Double =
        if (intervalMinutes <= 0L) Double.POSITIVE_INFINITY else 60.0 / intervalMinutes.toDouble()

    fun isWithinBudget(intervalMinutes: Long, maxWakeupsPerHour: Double): Boolean =
        estimateWakeupsPerHour(intervalMinutes) <= maxWakeupsPerHour
}

/**
 * Schedules background work for the app.
 *
 * Also owns the periodic flush of batched analytics events so that queued
 * session/screen/action events are dispatched even when the app is not in
 * the foreground.
 */
class BackgroundTaskScheduler(private val context: Context) {

    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    /**
     * Schedules the periodic flush of batched analytics events.
     *
     * Events are batched in [SessionAnalytics] and dispatched on this cadence
     * (or earlier when the batch size threshold is reached).
     */
    fun scheduleAnalyticsFlush() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val flushRequest = PeriodicWorkRequestBuilder<AnalyticsFlushWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ANALYTICS_FLUSH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            flushRequest
        )
    }

    companion object {
        private const val SYNC_WORK_NAME = "ethos_periodic_sync"
        private const val ANALYTICS_FLUSH_WORK_NAME = "ethos_analytics_flush"
    }
}

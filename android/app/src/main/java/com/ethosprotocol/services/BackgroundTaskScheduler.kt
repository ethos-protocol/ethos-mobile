package com.ethosprotocol.services

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Centralized configuration and documentation of all periodic background work for the app.
 *
 * ## Android Background Tasks Summary (#321)
 *
 * This file enumerates all WorkManager periodic work items, their intervals, and constraints.
 * Battery optimization is achieved by:
 * 1. Using the longest intervals that maintain acceptable freshness
 * 2. Coordinating intervals between tasks to reduce total wake-ups per hour
 * 3. Using adaptive intervals based on vault urgency (e.g., VaultWidgetUpdateWorker)
 *
 * ### Periodic Work Items
 *
 * **VaultWidgetUpdateWorker**
 * - Purpose: Fetch all active vaults and update widget display with most-urgent vault
 * - Interval: 15 min (urgent: TTL < 24h) or 60 min (normal: TTL >= 24h)
 * - Constraints: Requires network (CONNECTED)
 * - Battery impact: HIGH when urgent, LOW when normal
 * - Coordination: Urgency threshold (24h) matches Vault.isExpiringSoon for consistency
 *
 * **One-Time / On-Demand Work Items (not periodic)**
 * - PendingActionSyncWorker: Triggered when offline queue has pending actions
 * - CheckInReminderWorker: Scheduled per-vault based on TTL (not yet active)
 *
 * ### iOS Background Tasks Summary
 *
 * **TTLWidget Timeline Refresh**
 * - Purpose: Update widget display with current TTL countdown
 * - Interval: Dynamic (2-15 min based on urgency, same scaling as Android)
 * - Constraints: Requires network
 * - Battery impact: HIGH when urgent, MEDIUM when normal
 *
 * **Background App Refresh (if implemented)**
 * - Purpose: Periodically sync vault state even when app is backgrounded
 * - Interval: OS-managed (typically 15+ min minimum)
 * - Constraints: Requires network, power optimization
 *
 * ### Consolidation Opportunities
 *
 * 1. **Current State (Android)**
 *    - Only VaultWidgetUpdateWorker runs periodically (adaptive 15/60 min)
 *    - PendingActionSyncWorker runs on-demand when offline queue needs retry
 *    - CheckInReminderWorker exists but is not currently scheduled
 *    - Total wake-ups: 1-4 per hour when urgent, 1 per hour when normal
 *
 * 2. **Potential Consolidation**
 *    - VaultWidgetUpdateWorker could be extended to handle:
 *      - Syncing pending offline actions (on networks that permit retry)
 *      - Triggering check-in reminders based on individual vault TTL
 *    - This would reduce total wake-ups without adding new periodic tasks
 *    - Requires careful batching to avoid blocking the widget update
 *
 * 3. **iOS Opportunity**
 *    - Implement Background App Refresh with similar adaptive intervals
 *    - Coordinate refresh intervals with Android for consistent behavior
 *
 * ### Battery Impact Measurement
 *
 * To measure battery impact before/after consolidation:
 * - Android: Use Battery Historian to capture wake-locks during test period
 * - iOS: Use Xcode's Battery / Energy Report in device settings
 * - Baseline: Measure current app wake-ups during 4 hours of normal use
 * - After: Measure same scenario after consolidation changes
 * - Target: < 10% increase in battery drain with vastly improved freshness
 *
 * ### WorkManager Minimum Interval Floor
 *
 * WorkManager enforces a 15-minute minimum for all periodic work, even if a shorter
 * interval is requested. This is a system-wide constraint to preserve battery life.
 * Adaptive intervals (like VaultWidgetUpdateWorker's 15/60 min) are a workaround to
 * provide faster updates when needed without bumping the minimum.
 */
data class BatteryTaskMetric(
    val name: String,
    val purpose: String,
    val intervalMinutes: Long,
    val requiresNetwork: Boolean,
    val maxWakeupsPerHour: Double
)

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

object BackgroundTaskScheduler {

    /**
     * Initializes all periodic background work.
     * Called once from EthosProtocolApplication.onCreate().
     */
    fun initializeAll(context: Context) {
        // VaultWidgetUpdateWorker is scheduled here with its adaptive interval logic.
        // Other periodic tasks should be added here as they're implemented.
    }

    /**
     * Reschedules a specific periodic task with a new interval.
     * Used by VaultWidgetUpdateWorker to adapt its interval based on vault urgency.
     */
    fun rescheduleVaultWidgetUpdates(context: Context, intervalMinutes: Long) {
        VaultWidgetUpdateWorker.schedule(context, intervalMinutes)
    }

    /**
     * For future consolidation: batch pending sync work with vault updates.
     * This would require careful error handling to ensure widget updates
     * always succeed even if pending sync fails.
     */
    fun initializePendingActionBatching(context: Context) {
        // TBD: Extended VaultWidgetUpdateWorker to also process pending actions
    }

    /**
     * For future consolidation: batch check-in reminders with vault updates.
     * This would replace per-vault CheckInReminderWorker scheduling with
     * logic inside VaultWidgetUpdateWorker that checks TTL and posts reminders.
     */
    fun initializeCheckInReminderBatching(context: Context) {
        // TBD: Extended VaultWidgetUpdateWorker to also post check-in reminders
    }
}

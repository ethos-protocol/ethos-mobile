package com.ethosprotocol.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ethosprotocol.R
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class VaultStatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { updateWidget(context, manager, it) }
    }

    companion object {
        private const val PREFS = "vault_widget_prefs"
        private const val KEY_VAULT_ID = "vault_id"
        private const val KEY_VAULT_NAME = "vault_name"
        private const val KEY_TTL = "ttl_remaining"
        private const val KEY_LAST_CHECK_IN = "last_check_in"

        fun saveVaultData(context: Context, vaultId: String, vaultName: String, ttlRemaining: String, lastCheckIn: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_VAULT_ID, vaultId)
                .putString(KEY_VAULT_NAME, vaultName)
                .putString(KEY_TTL, ttlRemaining)
                .putString(KEY_LAST_CHECK_IN, lastCheckIn)
                .apply()
        }

        /** Builds the deep link used to open a widget tap directly onto the vault it displayed. */
        internal fun deepLinkUri(vaultId: String): String = "ethosprotocol://vault/$vaultId/view-details"

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val vaultId = prefs.getString(KEY_VAULT_ID, null)
            val vaultName = prefs.getString(KEY_VAULT_NAME, "—") ?: "—"
            val ttl = prefs.getString(KEY_TTL, "Unknown") ?: "Unknown"
            val lastCheckIn = prefs.getString(KEY_LAST_CHECK_IN, "Never") ?: "Never"

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!vaultId.isNullOrEmpty()) {
                    data = Uri.parse(deepLinkUri(vaultId))
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.vault_widget).apply {
                setTextViewText(R.id.widget_vault_name, vaultName)
                setTextViewText(R.id.widget_ttl, "TTL: $ttl")
                setTextViewText(R.id.widget_last_check_in, "Last check-in: $lastCheckIn")
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }
            manager.updateAppWidget(widgetId, views)
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VaultStatusWidget::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        /** Formats an ISO-8601 timestamp as a relative time ("2 hours ago") for widget display. */
        internal fun formatLastCheckIn(isoTimestamp: String, now: Instant = Instant.now()): String {
            val checkInInstant = runCatching { Instant.parse(isoTimestamp) }.getOrNull() ?: return isoTimestamp
            val seconds = Duration.between(checkInInstant, now).seconds.coerceAtLeast(0)
            return when {
                seconds < 60 -> "Just now"
                seconds < 3_600 -> relative(seconds / 60, "minute")
                seconds < 86_400 -> relative(seconds / 3_600, "hour")
                else -> relative(seconds / 86_400, "day")
            }
        }

        private fun relative(value: Long, unit: String): String =
            "$value $unit${if (value == 1L) "" else "s"} ago"
    }
}

@HiltWorker
class VaultWidgetUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiClient: ApiClient
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Pick the active vault with the lowest ttlRemaining — the same
        // "most urgent vault" selection that iOS TTLTimelineProvider uses
        // (min(by:) on ttlRemaining). Using firstOrNull() would show a
        // different vault than iOS for accounts with multiple vaults.
        val vault = (apiClient.listVaults() as? ApiResult.Success)?.data
            ?.filter { it.status == VaultStatus.active }
            ?.minByOrNull { it.ttlRemaining ?: Long.MAX_VALUE }

        if (vault != null) {
            VaultStatusWidget.saveVaultData(
                applicationContext,
                vaultId = vault.id,
                vaultName = vault.id.take(12) + "…",
                ttlRemaining = formatTtl(vault.ttlRemaining),
                lastCheckIn = VaultStatusWidget.formatLastCheckIn(vault.lastCheckIn)
            )
            VaultStatusWidget.refreshAll(applicationContext)
        }

        // Always queue the next run — this worker is its own scheduler, so skipping it on an
        // error or an empty vault list would stop the widget updating for good.
        schedule(applicationContext, determineUpdateInterval(vault?.ttlRemaining))
        return Result.success()
    }

    private fun formatTtl(seconds: Long?): String {
        if (seconds == null) return "Unknown"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        return if (days > 0) "${days}d ${hours}h" else "${hours}h"
    }

    companion object {
        const val WORK_NAME = "vault_widget_update"

        // Refresh interval tiers ported from iOS TTLWidget.computeNextUpdateInterval (#199):
        // the closer a vault is to expiring, the more often the widget is refreshed. The
        // 60-minute idle tier is Android-only — a vault more than a day from expiry moves too
        // slowly to be worth an hourly-or-better wake-up on a battery-budgeted device.
        private const val IDLE_INTERVAL_MINUTES = 60L
        private const val NORMAL_INTERVAL_MINUTES = 15L
        private const val ELEVATED_INTERVAL_MINUTES = 10L
        private const val URGENT_INTERVAL_MINUTES = 5L
        private const val CRITICAL_INTERVAL_MINUTES = 2L

        private const val IDLE_THRESHOLD_SECONDS = 86_400L    // 24h, matches Vault.isExpiringSoon
        private const val NORMAL_THRESHOLD_SECONDS = 21_600L  // 6h
        private const val ELEVATED_THRESHOLD_SECONDS = 3_600L // 1h
        private const val URGENT_THRESHOLD_SECONDS = 1_800L   // 30m

        /** Picks the widget refresh interval based on how close the most urgent vault is to expiring. */
        internal fun determineUpdateInterval(ttlRemainingSeconds: Long?): Long {
            val ttl = ttlRemainingSeconds ?: return IDLE_INTERVAL_MINUTES
            return when {
                ttl >= IDLE_THRESHOLD_SECONDS -> IDLE_INTERVAL_MINUTES
                ttl >= NORMAL_THRESHOLD_SECONDS -> NORMAL_INTERVAL_MINUTES
                ttl >= ELEVATED_THRESHOLD_SECONDS -> ELEVATED_INTERVAL_MINUTES
                ttl >= URGENT_THRESHOLD_SECONDS -> URGENT_INTERVAL_MINUTES
                else -> CRITICAL_INTERVAL_MINUTES
            }
        }

        /**
         * Queues the next widget refresh [intervalMinutes] from now.
         *
         * One-time work rather than periodic work: WorkManager enforces a 15-minute floor on
         * periodic intervals, which is too coarse for the near-expiry tiers. Each run
         * re-schedules the next one, mirroring iOS's `.after(nextUpdate)` timeline policy.
         */
        fun schedule(context: Context, intervalMinutes: Long = IDLE_INTERVAL_MINUTES) {
            val request = OneTimeWorkRequestBuilder<VaultWidgetUpdateWorker>()
                .setInitialDelay(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

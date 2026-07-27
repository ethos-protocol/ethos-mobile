package com.ethosprotocol.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@HiltWorker
class CheckInSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiClient: ApiClient,
    private val dao: PendingCheckInDao,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(context, params) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        val startMs = System.currentTimeMillis()
        val runAtIso = isoTimestamp(startMs)
        Log.i(TAG, "run started at=$runAtIso attempt=${runAttemptCount + 1}")

        val pending = dao.getAll()
        if (pending.isEmpty()) {
            recordOutcome(runAtIso, succeeded = 0, failed = 0, retrying = false)
            Log.i(TAG, "run finished — nothing to sync")
            return Result.success()
        }

        var hasRetryableFailure = false
        var succeeded = 0
        var permanentlyFailed = 0

        for (item in pending) {
            when (val result = apiClient.checkIn(item.vaultId)) {
                is ApiResult.Success -> {
                    dao.delete(item)
                    succeeded++
                    Log.i(TAG, "check-in synced vaultId=${item.vaultId}")
                }
                ApiResult.NetworkUnavailable -> {
                    hasRetryableFailure = true
                    Log.w(TAG, "check-in deferred (no network) vaultId=${item.vaultId}")
                }
                is ApiResult.Error -> {
                    // A check-in is a dead-man's-switch signal: silently dropping it on a
                    // transient failure (server error, timeout, expired auth) risks a vault
                    // being released even though the user did check in. Only drop the queued
                    // item when the server has definitively rejected the request as invalid
                    // (e.g. the vault no longer exists) — everything else is retried.
                    if (result.code in NON_RETRYABLE_ERROR_CODES) {
                        dao.delete(item)
                        permanentlyFailed++
                        Log.e(TAG, "check-in dropped (non-retryable) vaultId=${item.vaultId} code=${result.code} msg=${result.message}")
                    } else {
                        hasRetryableFailure = true
                        Log.w(TAG, "check-in deferred (retryable error) vaultId=${item.vaultId} code=${result.code} msg=${result.message}")
                    }
                }
            }
        }

        if (dao.getAll().isEmpty()) {
            notificationHelper.cancelQueuedCheckIn()
        }

        val willRetry = hasRetryableFailure
        recordOutcome(runAtIso, succeeded, permanentlyFailed, willRetry)

        val elapsedMs = System.currentTimeMillis() - startMs
        Log.i(TAG,
            "run finished elapsedMs=$elapsedMs succeeded=$succeeded permanentlyFailed=$permanentlyFailed willRetry=$willRetry"
        )

        return if (willRetry) Result.retry() else Result.success()
    }

    /**
     * Persists the last sync attempt timestamp and outcome to SharedPreferences so that
     * support/debug tooling can surface it without needing logcat access.
     */
    private fun recordOutcome(timestamp: String, succeeded: Int, failed: Int, retrying: Boolean) {
        prefs.edit()
            .putString(PREF_LAST_SYNC_AT, timestamp)
            .putInt(PREF_LAST_SYNC_SUCCEEDED, succeeded)
            .putInt(PREF_LAST_SYNC_FAILED, failed)
            .putBoolean(PREF_LAST_SYNC_RETRYING, retrying)
            .apply()
    }

    private fun isoTimestamp(epochMs: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(epochMs))

    companion object {
        const val WORK_NAME = "checkin_sync"
        const val PREFS_NAME = "checkin_sync_diagnostics"
        const val PREF_LAST_SYNC_AT = "last_sync_at"
        const val PREF_LAST_SYNC_SUCCEEDED = "last_sync_succeeded"
        const val PREF_LAST_SYNC_FAILED = "last_sync_failed"
        const val PREF_LAST_SYNC_RETRYING = "last_sync_retrying"
        private const val TAG = "CheckInSyncWorker"

        // Error codes where the server has told us unambiguously that this check-in can
        // never succeed (bad request / vault no longer exists), so retrying is pointless.
        // Everything else (5xx, 401, 0/exception) is treated as transient and retried.
        private val NON_RETRYABLE_ERROR_CODES = setOf(400, 404, 410)

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<CheckInSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Returns a snapshot of the last sync run's diagnostics for display in support/debug UI.
         * Returns null if no sync has ever run on this device.
         */
        fun lastSyncDiagnostics(context: Context): SyncDiagnostics? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val timestamp = prefs.getString(PREF_LAST_SYNC_AT, null) ?: return null
            return SyncDiagnostics(
                lastSyncAt = timestamp,
                succeeded = prefs.getInt(PREF_LAST_SYNC_SUCCEEDED, 0),
                failed = prefs.getInt(PREF_LAST_SYNC_FAILED, 0),
                isRetrying = prefs.getBoolean(PREF_LAST_SYNC_RETRYING, false)
            )
        }
    }
}

/** Snapshot of the last CheckInSyncWorker run, surfaced for support and debug use. */
data class SyncDiagnostics(
    val lastSyncAt: String,
    val succeeded: Int,
    val failed: Int,
    val isRetrying: Boolean
)

package com.ethosprotocol.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class CheckInSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiClient: ApiClient,
    private val dao: PendingCheckInDao,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = dao.getAll()
        if (pending.isEmpty()) return Result.success()

        var hasRetryableFailure = false
        for (item in pending) {
            when (val result = apiClient.checkIn(item.vaultId)) {
                is ApiResult.Success -> dao.delete(item)
                ApiResult.NetworkUnavailable -> hasRetryableFailure = true
                is ApiResult.Error -> {
                    // A check-in is a dead-man's-switch signal: silently dropping it on a
                    // transient failure (server error, timeout, expired auth) risks a vault
                    // being released even though the user did check in. Only drop the queued
                    // item when the server has definitively rejected the request as invalid
                    // (e.g. the vault no longer exists) — everything else is retried.
                    if (result.code in NON_RETRYABLE_ERROR_CODES) {
                        dao.delete(item)
                    } else {
                        hasRetryableFailure = true
                    }
                }
            }
        }

        if (dao.getAll().isEmpty()) {
            notificationHelper.cancelQueuedCheckIn()
        }

        return if (hasRetryableFailure) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME = "checkin_sync"

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
    }
}

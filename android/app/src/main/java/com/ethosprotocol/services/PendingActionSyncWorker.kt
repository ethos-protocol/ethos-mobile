package com.ethosprotocol.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.models.CreateVaultRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

@HiltWorker
class PendingActionSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiClient: ApiClient,
    private val dao: PendingActionDao,
    private val notificationHelper: NotificationHelper,
    private val networkMonitor: NetworkMonitor
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // WorkManager's NetworkType.CONNECTED constraint only guarantees an active network,
        // not one with real internet reachability (e.g. a captive-portal Wi-Fi network still
        // satisfies it). Re-check with NetworkMonitor, which validates NET_CAPABILITY_INTERNET,
        // before burning a retry cycle on requests that are certain to fail.
        if (!networkMonitor.isConnected) return Result.retry()

        val pending = dao.getAll()
        if (pending.isEmpty()) return Result.success()

        var hasRetryableFailure = false
        for (item in pending) {
            val result = when (item.type) {
                PendingActionType.CHECK_IN -> apiClient.checkIn(item.vaultId!!)
                PendingActionType.CREATE_VAULT ->
                    apiClient.createVault(Json.decodeFromString<CreateVaultRequest>(item.payloadJson!!))
            }
            when (result) {
                is ApiResult.Success -> dao.delete(item)
                ApiResult.NetworkUnavailable -> hasRetryableFailure = true
                is ApiResult.Error -> {
                    // A queued action represents user intent (a check-in, a vault the user
                    // filled out a form for): silently dropping it on a transient failure
                    // (server error, timeout, expired auth) would lose that intent. Only drop
                    // the item when the server has definitively rejected it as invalid (e.g.
                    // the vault no longer exists) — everything else is retried.
                    if (result.code in NON_RETRYABLE_ERROR_CODES) {
                        dao.delete(item)
                    } else {
                        hasRetryableFailure = true
                    }
                }
            }
        }

        if (dao.getAll().isEmpty()) {
            notificationHelper.cancelQueuedActions()
        }

        return if (hasRetryableFailure) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME = "pending_action_sync"

        // Error codes where the server has told us unambiguously that this action can
        // never succeed (bad request / vault no longer exists), so retrying is pointless.
        // Everything else (5xx, 401, 0/exception) is treated as transient and retried.
        private val NON_RETRYABLE_ERROR_CODES = setOf(400, 404, 410)

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingActionSyncWorker>()
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

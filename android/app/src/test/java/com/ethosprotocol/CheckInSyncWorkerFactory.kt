package com.ethosprotocol

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.services.CheckInSyncWorker
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingCheckInDao

/**
 * A [WorkerFactory] that injects test doubles (mocks) into [CheckInSyncWorker],
 * bypassing the Hilt/AssistedInject wiring that is not available in plain JVM unit tests.
 */
class CheckInSyncWorkerFactory(
    private val apiClient: ApiClient,
    private val dao: PendingCheckInDao,
    private val notificationHelper: NotificationHelper
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return if (workerClassName == CheckInSyncWorker::class.java.name) {
            CheckInSyncWorker(appContext, workerParameters, apiClient, dao, notificationHelper)
        } else {
            null
        }
    }
}

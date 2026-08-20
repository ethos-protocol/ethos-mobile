package com.ethosprotocol

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionSyncWorker

/**
 * A [WorkerFactory] that injects test doubles (mocks) into [PendingActionSyncWorker],
 * bypassing the Hilt/AssistedInject wiring that is not available in plain JVM unit tests.
 */
class CheckInSyncWorkerFactory(
    private val apiClient: ApiClient,
    private val dao: PendingActionDao,
    private val notificationHelper: NotificationHelper,
    private val networkMonitor: NetworkMonitor
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return if (workerClassName == PendingActionSyncWorker::class.java.name) {
            PendingActionSyncWorker(appContext, workerParameters, apiClient, dao, notificationHelper, networkMonitor)
        } else {
            null
        }
    }
}

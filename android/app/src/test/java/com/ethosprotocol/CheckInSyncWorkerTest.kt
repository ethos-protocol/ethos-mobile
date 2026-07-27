package com.ethosprotocol

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.services.CheckInSyncWorker
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingCheckIn
import com.ethosprotocol.services.PendingCheckInDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckInSyncWorkerTest {

    private val apiClient: ApiClient = mockk()
    private val dao: PendingCheckInDao = mockk()
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val networkMonitor: NetworkMonitor = mockk()
    private val context: Context = mockk(relaxed = true)
    private val params: WorkerParameters = mockk(relaxed = true)

    private fun worker() = CheckInSyncWorker(context, params, apiClient, dao, notificationHelper, networkMonitor)

    @Test
    fun `doWork retries immediately when network connected but not validated`() = runTest {
        // Simulates a captive-portal network: WorkManager's NetworkType.CONNECTED constraint
        // would have already let this worker run, but NetworkMonitor correctly reports no
        // real internet reachability.
        every { networkMonitor.isConnected } returns false

        val result = worker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) { dao.getAll() }
        coVerify(exactly = 0) { apiClient.checkIn(any()) }
    }

    @Test
    fun `doWork proceeds with sync when network is validated`() = runTest {
        every { networkMonitor.isConnected } returns true
        val pending = listOf(PendingCheckIn(vaultId = "v1", queuedAt = 0L))
        coEvery { dao.getAll() } returns pending
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Success(Unit)
        coEvery { dao.delete(any()) } returns Unit

        val result = worker().doWork()

        assertEquals(Result.success(), result)
        coVerify { apiClient.checkIn("v1") }
    }
}

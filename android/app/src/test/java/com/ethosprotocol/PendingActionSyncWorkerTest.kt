package com.ethosprotocol

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.models.CreateVaultRequest
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingAction
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.PendingActionType
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PendingActionSyncWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val params: WorkerParameters = mockk(relaxed = true)
    private val apiClient: ApiClient = mockk()
    private val dao: PendingActionDao = mockk()
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val networkMonitor: NetworkMonitor = mockk()
    private lateinit var worker: PendingActionSyncWorker

    @Before
    fun setup() {
        every { networkMonitor.isConnected } returns true
        worker = PendingActionSyncWorker(context, params, apiClient, dao, notificationHelper, networkMonitor)
    }

    @Test
    fun `empty queue succeeds without touching the api`() = runTest {
        coEvery { dao.getAll() } returns emptyList()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { apiClient.checkIn(any()) }
        coVerify(exactly = 0) { apiClient.createVault(any()) }
    }

    @Test
    fun `successful check-in is removed from the queue`() = runTest {
        val item = checkInAction("v1")
        coEvery { dao.getAll() } returnsMany listOf(listOf(item), emptyList())
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Success(Unit)
        coEvery { dao.delete(item) } just Runs

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { dao.delete(item) }
        verify { notificationHelper.cancelQueuedActions() }
    }

    @Test
    fun `network unavailable retries and keeps the item queued`() = runTest {
        val item = checkInAction("v1")
        coEvery { dao.getAll() } returns listOf(item)
        coEvery { apiClient.checkIn("v1") } returns ApiResult.NetworkUnavailable

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun `non-retryable error drops the item and still succeeds`() = runTest {
        val item = checkInAction("v1")
        coEvery { dao.getAll() } returnsMany listOf(listOf(item), emptyList())
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Error("Not found", 404)
        coEvery { dao.delete(item) } just Runs

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { dao.delete(item) }
    }

    @Test
    fun `retryable server error keeps the item queued and retries`() = runTest {
        val item = checkInAction("v1")
        coEvery { dao.getAll() } returns listOf(item)
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Error("Server error", 500)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun `mixed check-in and create-vault items are both dispatched and removed on success`() = runTest {
        val checkIn = checkInAction("v1")
        val vaultReq = CreateVaultRequest("GXYZ", 2_592_000L)
        val createVault = PendingAction(
            id = 2,
            type = PendingActionType.CREATE_VAULT,
            payloadJson = Json.encodeToString(kotlinx.serialization.serializer(), vaultReq),
            queuedAt = 2L
        )
        coEvery { dao.getAll() } returnsMany listOf(listOf(checkIn, createVault), emptyList())
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Success(Unit)
        coEvery { apiClient.createVault(vaultReq) } returns ApiResult.Success(makeVault())
        coEvery { dao.delete(any()) } just Runs

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { apiClient.createVault(vaultReq) }
        coVerify { dao.delete(checkIn) }
        coVerify { dao.delete(createVault) }
    }

    private fun checkInAction(vaultId: String) = PendingAction(
        id = 1,
        type = PendingActionType.CHECK_IN,
        vaultId = vaultId,
        queuedAt = 1L,
        dedupeKey = "check_in:$vaultId"
    )

    private fun makeVault() = Vault(
        id = "v2", owner = "GABC", beneficiary = "GXYZ",
        balance = 0L, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 2_592_000L,
        status = VaultStatus.active
    )
}

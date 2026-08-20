package com.ethosprotocol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingAction
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.PendingActionType
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [PendingActionSyncWorker] using [TestListenableWorkerBuilder].
 *
 * The worker enforces a dead-man's-switch invariant: a pending check-in must never
 * be silently dropped unless the server has definitively rejected the request with a
 * non-retryable status code ({400, 404, 410}).  All other failure modes (network
 * unavailable, 5xx, 401, …) must leave the item in the queue and return
 * [ListenableWorker.Result.retry].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CheckInSyncWorkerTest {

    private val apiClient: ApiClient = mockk()
    private val dao: PendingActionDao = mockk(relaxed = true)
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val networkMonitor: NetworkMonitor = mockk()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        every { networkMonitor.isConnected } returns true
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun buildWorker(): PendingActionSyncWorker =
        TestListenableWorkerBuilder<PendingActionSyncWorker>(context)
            .setWorkerFactory(
                CheckInSyncWorkerFactory(apiClient, dao, notificationHelper, networkMonitor)
            )
            .build()

    private fun item(id: String) = PendingAction(type = PendingActionType.CHECK_IN, vaultId = id, queuedAt = 1_000L)

    // ---------------------------------------------------------------------------
    // 1. Empty queue → success, nothing deleted, notification not cancelled
    // ---------------------------------------------------------------------------

    @Test
    fun `empty queue returns success without touching the DAO or notification`() = runBlocking {
        coEvery { dao.getAll() } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // No items to process so delete should never be called
        coVerify(exactly = 0) { dao.delete(any()) }
        // Queue is empty from the start so cancelQueuedCheckIn should not be called
        verify(exactly = 0) { notificationHelper.cancelQueuedActions() }
    }

    // ---------------------------------------------------------------------------
    // 2. All successes → all deleted, notification cancelled, returns success
    // ---------------------------------------------------------------------------

    @Test
    fun `all items succeed are deleted and notification is cancelled`() = runBlocking {
        val items = listOf(item("v1"), item("v2"), item("v3"))
        // First call returns the full list; subsequent calls (after deletes) return empty
        coEvery { dao.getAll() } returnsMany listOf(items, emptyList())
        coEvery { apiClient.checkIn(any()) } returns ApiResult.Success(Unit)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { dao.delete(item("v1")) }
        coVerify(exactly = 1) { dao.delete(item("v2")) }
        coVerify(exactly = 1) { dao.delete(item("v3")) }
        verify(exactly = 1) { notificationHelper.cancelQueuedActions() }
    }

    // ---------------------------------------------------------------------------
    // 3. All network-unavailable → nothing deleted, returns retry
    // ---------------------------------------------------------------------------

    @Test
    fun `all network-unavailable leaves queue intact and returns retry`() = runBlocking {
        val items = listOf(item("v1"), item("v2"))
        coEvery { dao.getAll() } returnsMany listOf(items, items) // second call: still full
        coEvery { apiClient.checkIn(any()) } returns ApiResult.NetworkUnavailable

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { dao.delete(any()) }
        verify(exactly = 0) { notificationHelper.cancelQueuedActions() }
    }

    // ---------------------------------------------------------------------------
    // 4. Non-retryable error codes (400, 404, 410) → only those items are deleted
    // ---------------------------------------------------------------------------

    @Test
    fun `non-retryable error code 400 deletes item`() = runBlocking {
        val items = listOf(item("v1"))
        coEvery { dao.getAll() } returnsMany listOf(items, emptyList())
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Error("Bad Request", 400)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { dao.delete(item("v1")) }
        verify(exactly = 1) { notificationHelper.cancelQueuedActions() }
    }

    @Test
    fun `non-retryable error code 404 deletes item`() = runBlocking {
        val items = listOf(item("v1"))
        coEvery { dao.getAll() } returnsMany listOf(items, emptyList())
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Error("Not Found", 404)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { dao.delete(item("v1")) }
        verify(exactly = 1) { notificationHelper.cancelQueuedActions() }
    }

    @Test
    fun `non-retryable error code 410 deletes item`() = runBlocking {
        val items = listOf(item("v1"))
        coEvery { dao.getAll() } returnsMany listOf(items, emptyList())
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Error("Gone", 410)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { dao.delete(item("v1")) }
        verify(exactly = 1) { notificationHelper.cancelQueuedActions() }
    }

    // ---------------------------------------------------------------------------
    // 5. Retryable error codes (e.g. 500, 401) → item NOT deleted, returns retry
    // ---------------------------------------------------------------------------

    @Test
    fun `retryable error code 500 does not delete item and returns retry`() = runBlocking {
        val items = listOf(item("v1"))
        coEvery { dao.getAll() } returnsMany listOf(items, items)
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Error("Server Error", 500)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { dao.delete(any()) }
        verify(exactly = 0) { notificationHelper.cancelQueuedActions() }
    }

    @Test
    fun `retryable error code 401 does not delete item and returns retry`() = runBlocking {
        val items = listOf(item("v1"))
        coEvery { dao.getAll() } returnsMany listOf(items, items)
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Error("Unauthorized", 401)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    // ---------------------------------------------------------------------------
    // 6. Mixed: one non-retryable, one network-unavailable, one success
    //    → non-retryable and success deleted; network-unavailable kept; returns retry
    // ---------------------------------------------------------------------------

    @Test
    fun `mixed results - non-retryable and success deleted, network error retained, returns retry`() =
        runBlocking {
            val vGone = item("gone")      // 410 → delete
            val vOffline = item("offline") // NetworkUnavailable → keep
            val vOk = item("ok")           // Success → delete
            val allItems = listOf(vGone, vOffline, vOk)

            // After deleting vGone and vOk, one item remains
            coEvery { dao.getAll() } returnsMany listOf(allItems, listOf(vOffline))
            coEvery { apiClient.checkIn("gone") } returns ApiResult.Error("Gone", 410)
            coEvery { apiClient.checkIn("offline") } returns ApiResult.NetworkUnavailable
            coEvery { apiClient.checkIn("ok") } returns ApiResult.Success(Unit)

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            coVerify(exactly = 1) { dao.delete(vGone) }
            coVerify(exactly = 1) { dao.delete(vOk) }
            coVerify(exactly = 0) { dao.delete(vOffline) }
            // Queue is not empty (vOffline remains) → do NOT cancel notification
            verify(exactly = 0) { notificationHelper.cancelQueuedActions() }
        }

    // ---------------------------------------------------------------------------
    // 7. Mixed: all non-retryable → all deleted, queue empty, returns success
    // ---------------------------------------------------------------------------

    @Test
    fun `all non-retryable - all deleted, queue drained, notification cancelled, returns success`() =
        runBlocking {
            val items = listOf(item("a"), item("b"))
            coEvery { dao.getAll() } returnsMany listOf(items, emptyList())
            coEvery { apiClient.checkIn("a") } returns ApiResult.Error("Not Found", 404)
            coEvery { apiClient.checkIn("b") } returns ApiResult.Error("Gone", 410)

            val result = buildWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 1) { dao.delete(item("a")) }
            coVerify(exactly = 1) { dao.delete(item("b")) }
            verify(exactly = 1) { notificationHelper.cancelQueuedActions() }
        }
}

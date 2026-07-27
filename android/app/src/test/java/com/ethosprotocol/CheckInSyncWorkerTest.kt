package com.ethosprotocol

import android.content.Context
import android.content.SharedPreferences
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.services.CheckInSyncWorker
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingCheckIn
import com.ethosprotocol.services.PendingCheckInDao
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for issue #62: CheckInSyncWorker must record a last-sync-attempt timestamp and outcome
 * to SharedPreferences so a diagnostic trail exists even without logcat access.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckInSyncWorkerTest {

    private val apiClient: ApiClient = mockk()
    private val dao: PendingCheckInDao = mockk(relaxed = true)
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val prefsEditor: SharedPreferences.Editor = mockk(relaxed = true)

    @Before
    fun setup() {
        every { context.getSharedPreferences(CheckInSyncWorker.PREFS_NAME, Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns prefsEditor
        every { prefsEditor.putString(any(), any()) } returns prefsEditor
        every { prefsEditor.putInt(any(), any()) } returns prefsEditor
        every { prefsEditor.putBoolean(any(), any()) } returns prefsEditor
    }

    private fun buildWorker(): CheckInSyncWorker =
        CheckInSyncWorker(context, mockk(relaxed = true), apiClient, dao, notificationHelper)

    @Test
    fun `records last sync timestamp on successful run`() = runTest {
        coEvery { dao.getAll() } returns listOf(PendingCheckIn("v1", 1000L)) andThen emptyList()
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Success(Unit)

        buildWorker().doWork()

        verify { prefsEditor.putString(eq(CheckInSyncWorker.PREF_LAST_SYNC_AT), any()) }
        verify { prefsEditor.putInt(CheckInSyncWorker.PREF_LAST_SYNC_SUCCEEDED, 1) }
        verify { prefsEditor.putInt(CheckInSyncWorker.PREF_LAST_SYNC_FAILED, 0) }
        verify { prefsEditor.putBoolean(CheckInSyncWorker.PREF_LAST_SYNC_RETRYING, false) }
        verify { prefsEditor.apply() }
    }

    @Test
    fun `records last sync timestamp on network failure`() = runTest {
        coEvery { dao.getAll() } returns listOf(PendingCheckIn("v1", 1000L))
        coEvery { apiClient.checkIn("v1") } returns ApiResult.NetworkUnavailable

        buildWorker().doWork()

        verify { prefsEditor.putString(eq(CheckInSyncWorker.PREF_LAST_SYNC_AT), any()) }
        verify { prefsEditor.putBoolean(CheckInSyncWorker.PREF_LAST_SYNC_RETRYING, true) }
        verify { prefsEditor.apply() }
    }

    @Test
    fun `records failed count when item is permanently dropped`() = runTest {
        coEvery { dao.getAll() } returns listOf(PendingCheckIn("v1", 1000L)) andThen emptyList()
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Error("Not found", 404)

        buildWorker().doWork()

        verify { prefsEditor.putInt(CheckInSyncWorker.PREF_LAST_SYNC_FAILED, 1) }
        verify { prefsEditor.putBoolean(CheckInSyncWorker.PREF_LAST_SYNC_RETRYING, false) }
        verify { prefsEditor.apply() }
    }

    @Test
    fun `returns success when queue is empty`() = runTest {
        coEvery { dao.getAll() } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // Diagnostics still written even for an empty run
        verify { prefsEditor.putString(eq(CheckInSyncWorker.PREF_LAST_SYNC_AT), any()) }
    }

    @Test
    fun `returns retry when network unavailable`() = runTest {
        coEvery { dao.getAll() } returns listOf(PendingCheckIn("v1", 1000L))
        coEvery { apiClient.checkIn("v1") } returns ApiResult.NetworkUnavailable

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
    }
}

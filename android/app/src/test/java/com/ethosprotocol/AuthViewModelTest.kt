package com.ethosprotocol

import android.content.Context
import androidx.work.WorkManager
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.RecoveryInitiateRequest
import com.ethosprotocol.models.RecoveryInitiateResponse
import com.ethosprotocol.services.CheckInSyncWorker
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.services.PendingCheckInDao
import com.ethosprotocol.ui.AuthViewModel
import com.ethosprotocol.widget.VaultStatusWidget
import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private val passkeyService: PasskeyService = mockk()
    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val pendingCheckInDao: PendingCheckInDao = mockk(relaxed = true)
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private lateinit var vm: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(VaultStatusWidget.Companion)
        every { VaultStatusWidget.clearVaultData(any()) } just Runs
        every { VaultStatusWidget.refreshAll(any()) } just Runs
        every { tokenProvider.token } returns "existing-token"
        vm = AuthViewModel(apiClient, passkeyService, tokenProvider, pendingCheckInDao, notificationHelper, workManager, context)
        vm.relockTimeoutMillis = 30_000L
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkObject(VaultStatusWidget.Companion)
    }

    @Test
    fun `resuming before timeout does not lock`() {
        assertTrue(vm.state.value.isAuthenticated)

        vm.onAppBackgrounded(now = 1_000L)
        vm.onAppForegrounded(now = 1_000L + 29_999L)

        assertFalse(vm.state.value.isLocked)
    }

    @Test
    fun `resuming after timeout locks the session`() {
        vm.onAppBackgrounded(now = 1_000L)
        vm.onAppForegrounded(now = 1_000L + 30_000L)

        assertTrue(vm.state.value.isLocked)
    }

    @Test
    fun `unlock clears the locked state`() {
        vm.onAppBackgrounded(now = 1_000L)
        vm.onAppForegrounded(now = 1_000L + 60_000L)
        assertTrue(vm.state.value.isLocked)

        vm.unlock()

        assertFalse(vm.state.value.isLocked)
    }

    @Test
    fun `backgrounding while signed out does not arm the timer`() {
        every { tokenProvider.token } returns null
        val signedOutVm = AuthViewModel(
            apiClient, passkeyService, tokenProvider, pendingCheckInDao, notificationHelper, workManager, context
        )

        signedOutVm.onAppBackgrounded(now = 1_000L)
        signedOutVm.onAppForegrounded(now = 1_000L + 60_000L)

        assertFalse(signedOutVm.state.value.isLocked)
    }

    @Test
    fun `signOut resets locked state`() = runTest {
        vm.onAppBackgrounded(now = 1_000L)
        vm.onAppForegrounded(now = 1_000L + 60_000L)
        assertTrue(vm.state.value.isLocked)

        vm.signOut()

        assertFalse(vm.state.value.isLocked)
        assertFalse(vm.state.value.isAuthenticated)
    }

    @Test
    fun `signOut clears every surface that could leak vault data to the next user`() = runTest {
        vm.signOut()

        verify { tokenProvider.clear() }
        coVerify { pendingCheckInDao.deleteAll() }
        verify { workManager.cancelUniqueWork(CheckInSyncWorker.WORK_NAME) }
        verify { workManager.cancelUniqueWork(VaultWidgetUpdateWorker.WORK_NAME) }
        verify { VaultStatusWidget.clearVaultData(context) }
        verify { VaultStatusWidget.refreshAll(context) }
        verify { notificationHelper.cancelQueuedCheckIn() }
    }

    @Test
    fun `initiateRecovery success stores the recovery token`() = runTest {
        coEvery { apiClient.initiateRecovery(RecoveryInitiateRequest("alice")) } returns
            ApiResult.Success(RecoveryInitiateResponse("recovery-token", "2026-01-01T00:00:00Z"))

        vm.initiateRecovery("alice")

        assertEquals("recovery-token", vm.state.value.recoveryToken)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `completeRecovery signs in and clears the recovery token on success`() = runTest {
        val activity: android.app.Activity = mockk(relaxed = true)
        coEvery { apiClient.initiateRecovery(RecoveryInitiateRequest("alice")) } returns
            ApiResult.Success(RecoveryInitiateResponse("recovery-token", "2026-01-01T00:00:00Z"))
        vm.initiateRecovery("alice")

        coEvery { passkeyService.recoverAccount(activity, "alice", "recovery-token") } returns Result.success(Unit)
        coEvery { passkeyService.authenticate(activity) } returns Result.success(Unit)

        vm.completeRecovery(activity, "alice")

        coVerify { passkeyService.recoverAccount(activity, "alice", "recovery-token") }
        assertNull(vm.state.value.recoveryToken)
        assertTrue(vm.state.value.isAuthenticated)
    }

    @Test
    fun `cancelRecovery clears the recovery token`() = runTest {
        coEvery { apiClient.initiateRecovery(RecoveryInitiateRequest("alice")) } returns
            ApiResult.Success(RecoveryInitiateResponse("recovery-token", "2026-01-01T00:00:00Z"))
        vm.initiateRecovery("alice")
        assertNotNull(vm.state.value.recoveryToken)

        vm.cancelRecovery()

        assertNull(vm.state.value.recoveryToken)
    }
}

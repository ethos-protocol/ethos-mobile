package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.RecoveryInitiateRequest
import com.ethosprotocol.models.RecoveryInitiateResponse
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.ui.AuthViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #212 — Verifies the client-side rate-limiting logic for recovery-code submission in
 * [AuthViewModel], mirroring [OTPRateLimiterAndroidTest] / iOS's OTPRateLimiter (#119).
 *
 * Cooldown schedule under test:
 *   < 3 failures  → no cooldown
 *   3 failures    → 30 s
 *   4 failures    → 60 s
 *   5+ failures   → 120 s (capped)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RecoveryRateLimiterAndroidTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val passkeyService: PasskeyService = mockk()
    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val apiClient: ApiClient = mockk()
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val pendingActionDao: PendingActionDao = mockk(relaxed = true)
    private val activity: android.app.Activity = mockk(relaxed = true)
    private lateinit var vm: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { tokenProvider.token } returns null
        every { tokenProvider.pushToken } returns null
        vm = AuthViewModel(
            apiClient = apiClient,
            passkeyService = passkeyService,
            tokenProvider = tokenProvider,
            notificationHelper = notificationHelper,
            pendingActionDao = pendingActionDao
        )
        coEvery { apiClient.initiateRecovery(any()) } returns
            ApiResult.Success(RecoveryInitiateResponse(recoveryToken = "recovery-token", expiresAt = "2099-01-01T00:00:00Z"))
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    // ── Cooldown schedule ─────────────────────────────────────────────────

    @Test
    fun `cooldownSeconds 1 failure returns 0`() {
        assertEquals(0, vm.recoveryCooldownSeconds(1))
    }

    @Test
    fun `cooldownSeconds 3 failures returns 30`() {
        assertEquals(30, vm.recoveryCooldownSeconds(3))
    }

    @Test
    fun `cooldownSeconds 4 failures returns 60`() {
        assertEquals(60, vm.recoveryCooldownSeconds(4))
    }

    @Test
    fun `cooldownSeconds 100 failures is capped at 120`() {
        assertEquals(120, vm.recoveryCooldownSeconds(100))
    }

    // ── Failure recording ─────────────────────────────────────────────────

    @Test
    fun `1st and 2nd failures increment count with no cooldown`() = runTest {
        coEvery { passkeyService.recoverAccount(any(), any(), any()) } returns
            Result.failure(RuntimeException("bad code"))

        vm.initiateRecovery("alice")
        vm.finishRecovery(activity, "alice")
        vm.finishRecovery(activity, "alice")

        assertEquals(2, vm.state.value.recoveryFailureCount)
        assertFalse(vm.state.value.isRecoveryBlocked)
    }

    @Test
    fun `3rd failure triggers 30s cooldown`() = runTest {
        coEvery { passkeyService.recoverAccount(any(), any(), any()) } returns
            Result.failure(RuntimeException("bad code"))

        vm.initiateRecovery("alice")
        repeat(3) { vm.finishRecovery(activity, "alice") }

        assertEquals(3, vm.state.value.recoveryFailureCount)
        assertTrue("Should be blocked after 3 failures", vm.state.value.isRecoveryBlocked)
        assertEquals(30, vm.state.value.recoveryCooldownSeconds)
    }

    @Test
    fun `finishRecovery while blocked does not call passkeyService`() = runTest {
        coEvery { passkeyService.recoverAccount(any(), any(), any()) } returns
            Result.failure(RuntimeException("bad code"))

        vm.initiateRecovery("alice")
        repeat(3) { vm.finishRecovery(activity, "alice") }
        assertTrue("Precondition: should be blocked", vm.state.value.isRecoveryBlocked)

        clearMocks(passkeyService, answers = false)
        vm.finishRecovery(activity, "alice")

        coVerify(exactly = 0) { passkeyService.recoverAccount(any(), any(), any()) }
    }

    // ── Reset on success ──────────────────────────────────────────────────

    @Test
    fun `success resets failure count and authenticates`() = runTest {
        coEvery { passkeyService.recoverAccount(any(), any(), any()) } returnsMany listOf(
            Result.failure(RuntimeException("bad")),
            Result.failure(RuntimeException("bad")),
            Result.success(Unit)
        )

        vm.initiateRecovery("alice")
        vm.finishRecovery(activity, "alice") // failure 1
        vm.finishRecovery(activity, "alice") // failure 2
        vm.finishRecovery(activity, "alice") // success

        assertEquals(0, vm.state.value.recoveryFailureCount)
        assertFalse(vm.state.value.isRecoveryBlocked)
        assertTrue(vm.state.value.isAuthenticated)
    }

    @Test
    fun `dismissRecovery clears rate-limit state`() = runTest {
        coEvery { passkeyService.recoverAccount(any(), any(), any()) } returns
            Result.failure(RuntimeException("bad code"))

        vm.initiateRecovery("alice")
        repeat(3) { vm.finishRecovery(activity, "alice") }
        assertTrue(vm.state.value.isRecoveryBlocked)

        vm.dismissRecovery()

        assertEquals(0, vm.state.value.recoveryFailureCount)
        assertEquals(0, vm.state.value.recoveryCooldownSeconds)
        assertNull(vm.state.value.recoveryToken)
    }
}

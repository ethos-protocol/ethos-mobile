package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.services.PendingAction
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionType
import com.ethosprotocol.ui.AuthViewModel
import io.mockk.*
import kotlinx.coroutines.CancellationException
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
 * Also covers issue #61: queued pending actions and their ongoing notification must be
 * cleared on sign-out so that state from the previous session cannot sync after the user
 * signs out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AuthViewModelTest {

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
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signOut with saved push token unregisters it before clearing local state`() = runTest {
        every { tokenProvider.pushToken } returns "fcm-token-123"
        coEvery { apiClient.unregisterPushToken("fcm-token-123") } returns ApiResult.Success(Unit)

        vm.signOut()

        coVerify { apiClient.unregisterPushToken("fcm-token-123") }
        verify { tokenProvider.clear() }
        assertFalse(vm.state.value.isAuthenticated)
    }

    @Test
    fun `signOut with no saved push token does not call unregisterPushToken`() = runTest {
        every { tokenProvider.pushToken } returns null

        vm.signOut()

        coVerify(exactly = 0) { apiClient.unregisterPushToken(any()) }
        verify { tokenProvider.clear() }
        assertFalse(vm.state.value.isAuthenticated)
    }

    @Test
    fun `signOut clears local state even when unregister fails`() = runTest {
        every { tokenProvider.pushToken } returns "fcm-token-123"
        coEvery { apiClient.unregisterPushToken(any()) } returns ApiResult.NetworkUnavailable

        vm.signOut()

        verify { tokenProvider.clear() }
        assertFalse(vm.state.value.isAuthenticated)
    }

    @Test
    fun `signIn cancelled mid-request does not surface an error`() = runTest {
        // Simulates the screen (and viewModelScope) being torn down while
        // passkeyService.authenticate() is in flight — the coroutine should stop
        // silently instead of writing a stray error/loading update to dead state.
        coEvery { passkeyService.authenticate(any()) } throws CancellationException("scope cancelled")

        vm.signIn(mockk(relaxed = true))

        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
        assertFalse(vm.state.value.isAuthenticated)
    }

    @Test
    fun `signOut deletes all pending actions`() = runTest {
        vm.signOut()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { pendingActionDao.deleteAll() }
    }

    @Test
    fun `signOut cancels queued action notification`() = runTest {
        vm.signOut()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { notificationHelper.cancelQueuedActions() }
    }

    @Test
    fun `signOut discards queued actions even when multiple are pending`() = runTest {
        val items = listOf(
            PendingAction(id = 1L, type = PendingActionType.CHECK_IN, vaultId = "vault-1", queuedAt = 1000L),
            PendingAction(id = 2L, type = PendingActionType.CHECK_IN, vaultId = "vault-2", queuedAt = 2000L)
        )
        coEvery { pendingActionDao.getAll() } returns items

        vm.signOut()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { pendingActionDao.deleteAll() }
        verify(exactly = 1) { notificationHelper.cancelQueuedActions() }
    }

    // MARK: - Sign-in cooldown

    @Test
    fun `signIn success authenticates with no cooldown`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.success(Unit)

        vm.signIn(activity)

        assertTrue(vm.state.value.isAuthenticated)
        assertEquals(0, vm.state.value.cooldownRemainingSeconds)
    }

    @Test
    fun `signIn failure below threshold does not start cooldown`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))

        vm.signIn(activity)
        vm.signIn(activity)

        assertEquals(0, vm.state.value.cooldownRemainingSeconds)
    }

    @Test
    fun `signIn failure reaching threshold starts base cooldown`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))

        repeat(3) { vm.signIn(activity) }

        assertEquals(2, vm.state.value.cooldownRemainingSeconds)
    }

    @Test
    fun `cooldown escalates exponentially with further failures`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))

        // signIn() is a no-op while a cooldown is active (verified separately), so each
        // further failure needs its predecessor's cooldown to actually finish counting down
        // first — otherwise the call never reaches passkeyService.authenticate() at all.
        repeat(3) { vm.signIn(activity) }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.state.value.cooldownRemainingSeconds)

        vm.signIn(activity)
        assertEquals(4, vm.state.value.cooldownRemainingSeconds)

        testDispatcher.scheduler.advanceUntilIdle()
        vm.signIn(activity)
        assertEquals(8, vm.state.value.cooldownRemainingSeconds)
    }

    @Test
    fun `cooldown is clamped to max`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))

        // signIn() is a no-op while a cooldown is active, so each iteration's failure only
        // actually registers once the previous cooldown has fully counted down.
        repeat(10) {
            testDispatcher.scheduler.advanceUntilIdle()
            vm.signIn(activity)
        }

        assertEquals(60, vm.state.value.cooldownRemainingSeconds)
    }

    @Test
    fun `signIn is a no-op while cooldown is active`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))
        repeat(3) { vm.signIn(activity) }
        assertEquals(2, vm.state.value.cooldownRemainingSeconds)

        vm.signIn(activity)

        coVerify(exactly = 3) { passkeyService.authenticate(activity) }
    }

    @Test
    fun `successful signIn resets failure count and cooldown`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))
        repeat(2) { vm.signIn(activity) }
        assertEquals(0, vm.state.value.cooldownRemainingSeconds)

        coEvery { passkeyService.authenticate(activity) } returns Result.success(Unit)
        vm.signIn(activity)
        assertTrue(vm.state.value.isAuthenticated)

        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad again"))
        repeat(2) { vm.signIn(activity) }

        assertEquals(0, vm.state.value.cooldownRemainingSeconds)
    }

    @Test
    fun `signOut clears cooldown state`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))
        repeat(3) { vm.signIn(activity) }
        assertEquals(2, vm.state.value.cooldownRemainingSeconds)

        vm.signOut()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, vm.state.value.cooldownRemainingSeconds)
    }
}

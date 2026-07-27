package com.ethosprotocol

import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.services.PendingCheckIn
import com.ethosprotocol.services.PendingCheckInDao
import com.ethosprotocol.ui.AuthViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for issue #61: queued check-ins and their ongoing notification must be cleared on sign-out
 * so that a pending check-in from the previous session cannot sync after the user signs out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val passkeyService: PasskeyService = mockk(relaxed = true)
    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val pendingCheckInDao: PendingCheckInDao = mockk(relaxed = true)
    private lateinit var vm: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { tokenProvider.token } returns "tok"
        vm = AuthViewModel(passkeyService, tokenProvider, notificationHelper, pendingCheckInDao)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signOut clears token and updates unauthenticated state`() = runTest {
        vm.signOut()

        verify { tokenProvider.clear() }
        assertFalse(vm.state.value.isAuthenticated)
    }

    @Test
    fun `signOut deletes all pending check-ins`() = runTest {
        vm.signOut()

        coVerify { pendingCheckInDao.deleteAll() }
    }

    @Test
    fun `signOut cancels queued check-in notification`() = runTest {
        vm.signOut()

        verify { notificationHelper.cancelQueuedCheckIn() }
    }

    @Test
    fun `signOut discards queued check-ins even when multiple are pending`() = runTest {
        val items = listOf(
            PendingCheckIn("vault-1", 1000L),
            PendingCheckIn("vault-2", 2000L)
        )
        coEvery { pendingCheckInDao.getAll() } returns items

        vm.signOut()

        coVerify(exactly = 1) { pendingCheckInDao.deleteAll() }
        verify(exactly = 1) { notificationHelper.cancelQueuedCheckIn() }
    }
}

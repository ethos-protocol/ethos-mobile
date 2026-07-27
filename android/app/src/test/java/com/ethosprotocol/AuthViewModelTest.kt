package com.ethosprotocol

import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.ui.AuthViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val passkeyService: PasskeyService = mockk()
    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private lateinit var vm: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { tokenProvider.token } returns "existing-token"
        vm = AuthViewModel(passkeyService, tokenProvider)
        vm.relockTimeoutMillis = 30_000L
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
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
        val signedOutVm = AuthViewModel(passkeyService, tokenProvider)

        signedOutVm.onAppBackgrounded(now = 1_000L)
        signedOutVm.onAppForegrounded(now = 1_000L + 60_000L)

        assertFalse(signedOutVm.state.value.isLocked)
    }

    @Test
    fun `signOut resets locked state`() {
        vm.onAppBackgrounded(now = 1_000L)
        vm.onAppForegrounded(now = 1_000L + 60_000L)
        assertTrue(vm.state.value.isLocked)

        vm.signOut()

        assertFalse(vm.state.value.isLocked)
        assertFalse(vm.state.value.isAuthenticated)
    }
}

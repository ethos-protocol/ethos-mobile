package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.ui.AuthViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val passkeyService: PasskeyService = mockk()
    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val apiClient: ApiClient = mockk()
    private lateinit var vm: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { tokenProvider.token } returns null
        vm = AuthViewModel(passkeyService, tokenProvider, apiClient)
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
}

package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.ui.AcceptanceViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// #109 — token is required by the server; verify it flows through the ViewModel.
@OptIn(ExperimentalCoroutinesApi::class)
class AcceptanceViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private lateinit var vm: AcceptanceViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        vm = AcceptanceViewModel(apiClient)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `accept success sets isAccepted true`() = runTest {
        coEvery { apiClient.acceptBeneficiary("vault-1", "tok-abc") } returns ApiResult.Success(Unit)

        vm.accept("vault-1", "tok-abc")

        assertTrue(vm.state.value.isAccepted)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `accept forwards token to apiClient`() = runTest {
        coEvery { apiClient.acceptBeneficiary(any(), any()) } returns ApiResult.Success(Unit)

        vm.accept("vault-xyz", "secret-token-123")

        coVerify { apiClient.acceptBeneficiary("vault-xyz", "secret-token-123") }
    }

    @Test
    fun `accept error sets error message`() = runTest {
        coEvery {
            apiClient.acceptBeneficiary("vault-1", "bad-token")
        } returns ApiResult.Error("Token invalid", 401)

        vm.accept("vault-1", "bad-token")

        assertFalse(vm.state.value.isAccepted)
        assertEquals("Token invalid", vm.state.value.error)
    }

    @Test
    fun `accept network unavailable sets error`() = runTest {
        coEvery { apiClient.acceptBeneficiary(any(), any()) } returns ApiResult.NetworkUnavailable

        vm.accept("vault-1", "tok-abc")

        assertFalse(vm.state.value.isAccepted)
        assertNotNull(vm.state.value.error)
    }
}

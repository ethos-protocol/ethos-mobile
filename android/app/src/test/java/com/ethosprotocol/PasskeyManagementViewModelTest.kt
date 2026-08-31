package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.PasskeyCredential
import com.ethosprotocol.ui.PasskeyManagementViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PasskeyManagementViewModel] (#206) — listing and revoking passkey
 * credentials for the authenticated account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasskeyManagementViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private lateinit var vm: PasskeyManagementViewModel

    private val credentialA = PasskeyCredential(
        credentialId = "cred-a", deviceLabel = "iPhone", createdAt = "2099-01-01T00:00:00Z", lastUsedAt = null
    )
    private val credentialB = PasskeyCredential(
        credentialId = "cred-b", deviceLabel = "Pixel", createdAt = "2099-01-01T00:00:00Z", lastUsedAt = "2099-01-02T00:00:00Z"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        vm = PasskeyManagementViewModel(apiClient)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load success populates credentials`() = runTest {
        coEvery { apiClient.listCredentials() } returns ApiResult.Success(listOf(credentialA, credentialB))

        vm.load()

        assertEquals(listOf(credentialA, credentialB), vm.state.value.credentials)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `load error sets error message and clears loading`() = runTest {
        coEvery { apiClient.listCredentials() } returns ApiResult.Error("Unauthorized", 401)

        vm.load()

        assertEquals("Unauthorized", vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.credentials.isEmpty())
    }

    @Test
    fun `revokeCredentialAfterBiometric success removes only that credential`() = runTest {
        coEvery { apiClient.listCredentials() } returns ApiResult.Success(listOf(credentialA, credentialB))
        vm.load()
        coEvery { apiClient.revokeCredential("cred-a") } returns ApiResult.Success(Unit)

        vm.revokeCredentialAfterBiometric("cred-a")

        assertEquals(listOf(credentialB), vm.state.value.credentials)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `revokeCredentialAfterBiometric error keeps credential and sets error`() = runTest {
        coEvery { apiClient.listCredentials() } returns ApiResult.Success(listOf(credentialA))
        vm.load()
        coEvery { apiClient.revokeCredential("cred-a") } returns ApiResult.Error("Cannot revoke current session credential", 409)

        vm.revokeCredentialAfterBiometric("cred-a")

        assertEquals(listOf(credentialA), vm.state.value.credentials)
        assertEquals("Cannot revoke current session credential", vm.state.value.error)
    }
}

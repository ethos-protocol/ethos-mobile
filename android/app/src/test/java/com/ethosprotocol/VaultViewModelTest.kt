package com.ethosprotocol

import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.VaultUiState
import com.ethosprotocol.ui.VaultViewModel
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.services.CheckInSyncWorker
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingCheckInDao
import android.content.Context
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val pendingCheckInDao: PendingCheckInDao = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private lateinit var vm: VaultViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(CheckInSyncWorker.Companion)
        every { CheckInSyncWorker.schedule(any()) } just Runs
        vm = VaultViewModel(apiClient, notificationHelper, pendingCheckInDao, context)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkObject(CheckInSyncWorker.Companion)
    }

    @Test
    fun `load success updates vaults`() = runTest {
        val vaults = listOf(makeVault("v1"), makeVault("v2"))
        coEvery { apiClient.listVaults() } returns ApiResult.Success(vaults)

        vm.load()

        assertEquals(vaults, vm.state.value.vaults)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `load network unavailable sets offline flag`() = runTest {
        coEvery { apiClient.listVaults() } returns ApiResult.NetworkUnavailable

        vm.load()

        assertTrue(vm.state.value.isOffline)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `load error sets error message`() = runTest {
        coEvery { apiClient.listVaults() } returns ApiResult.Error("Server error", 500)

        vm.load()

        assertEquals("Server error", vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `checkIn success refreshes only the checked-in vault`() = runTest {
        val v1 = makeVault("v1")
        val v2 = makeVault("v2")
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(v1, v2))
        vm.load()

        val refreshedV1 = v1.copy(lastCheckIn = "2026-05-01T00:00:00Z", ttlRemaining = 2_592_000L)
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Success(Unit)
        coEvery { apiClient.getVault("v1") } returns ApiResult.Success(refreshedV1)

        vm.checkIn("v1")

        coVerify { apiClient.checkIn("v1") }
        coVerify { apiClient.getVault("v1") }
        coVerify(exactly = 1) { apiClient.listVaults() }
        coVerify(exactly = 0) { apiClient.getVault("v2") }
        assertEquals(listOf(refreshedV1, v2), vm.state.value.vaults)
    }

    @Test
    fun `checkIn network unavailable sets error`() = runTest {
        coEvery { apiClient.checkIn("v1") } returns ApiResult.NetworkUnavailable

        vm.checkIn("v1")

        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `updateBeneficiary success updates only the target vault`() = runTest {
        val v1 = makeVault("v1")
        val v2 = makeVault("v2")
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(v1, v2))
        vm.load()

        val updatedV1 = v1.copy(beneficiary = "GNEWBENEFICIARY")
        coEvery { apiClient.updateBeneficiary("v1", "GNEWBENEFICIARY") } returns ApiResult.Success(updatedV1)

        vm.updateBeneficiary("v1", "GNEWBENEFICIARY")

        coVerify { apiClient.updateBeneficiary("v1", "GNEWBENEFICIARY") }
        assertEquals(listOf(updatedV1, v2), vm.state.value.vaults)
    }

    @Test
    fun `updateBeneficiary error sets error message`() = runTest {
        coEvery { apiClient.updateBeneficiary("v1", "GNEW") } returns ApiResult.Error("Server error", 500)

        vm.updateBeneficiary("v1", "GNEW")

        assertEquals("Server error", vm.state.value.error)
    }

    @Test
    fun `updateBeneficiary network unavailable sets error`() = runTest {
        coEvery { apiClient.updateBeneficiary("v1", "GNEW") } returns ApiResult.NetworkUnavailable

        vm.updateBeneficiary("v1", "GNEW")

        assertNotNull(vm.state.value.error)
    }

    private fun makeVault(id: String) = Vault(
        id = id, owner = "GABC", beneficiary = "GXYZ",
        balance = 10_000_000L, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 172_800L,
        status = VaultStatus.active
    )
}

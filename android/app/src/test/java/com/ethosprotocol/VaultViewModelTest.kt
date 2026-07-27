package com.ethosprotocol

import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultEvent
import com.ethosprotocol.models.VaultPage
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.VaultUiState
import com.ethosprotocol.ui.VaultViewModel
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.services.CheckInSyncWorker
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingCheckInDao
import com.ethosprotocol.services.VaultEventSocket
import android.content.Context
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
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
    private val vaultEventSocket: VaultEventSocket = mockk()
    private val context: Context = mockk(relaxed = true)
    private lateinit var vm: VaultViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(CheckInSyncWorker.Companion)
        every { CheckInSyncWorker.schedule(any()) } just Runs
        every { vaultEventSocket.events(any()) } returns emptyFlow()
        vm = VaultViewModel(apiClient, notificationHelper, pendingCheckInDao, vaultEventSocket, context)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkObject(CheckInSyncWorker.Companion)
    }

    @Test
    fun `load success updates vaults`() = runTest {
        val vaults = listOf(makeVault("v1"), makeVault("v2"))
        coEvery { apiClient.listVaults(offset = 0, limit = 20) } returns
            ApiResult.Success(VaultPage(vaults, nextOffset = null, hasMore = false))

        vm.load()

        assertEquals(vaults, vm.state.value.vaults)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasMore)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `load network unavailable sets offline flag`() = runTest {
        coEvery { apiClient.listVaults(offset = 0, limit = 20) } returns ApiResult.NetworkUnavailable

        vm.load()

        assertTrue(vm.state.value.isOffline)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `load error sets error message`() = runTest {
        coEvery { apiClient.listVaults(offset = 0, limit = 20) } returns ApiResult.Error("Server error", 500)

        vm.load()

        assertEquals("Server error", vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `load with more pages sets hasMore`() = runTest {
        val vaults = listOf(makeVault("v1"))
        coEvery { apiClient.listVaults(offset = 0, limit = 20) } returns
            ApiResult.Success(VaultPage(vaults, nextOffset = 20, hasMore = true))

        vm.load()

        assertTrue(vm.state.value.hasMore)
    }

    @Test
    fun `loadMore appends the next page using the returned offset`() = runTest {
        val firstPage = listOf(makeVault("v1"))
        val secondPage = listOf(makeVault("v2"))
        coEvery { apiClient.listVaults(offset = 0, limit = 20) } returns
            ApiResult.Success(VaultPage(firstPage, nextOffset = 20, hasMore = true))
        coEvery { apiClient.listVaults(offset = 20, limit = 20) } returns
            ApiResult.Success(VaultPage(secondPage, nextOffset = null, hasMore = false))

        vm.load()
        vm.loadMore()

        assertEquals(firstPage + secondPage, vm.state.value.vaults)
        assertFalse(vm.state.value.hasMore)
        assertFalse(vm.state.value.isLoadingMore)
        coVerify { apiClient.listVaults(offset = 20, limit = 20) }
    }

    @Test
    fun `loadMore is a no-op when hasMore is false`() = runTest {
        val vaults = listOf(makeVault("v1"))
        coEvery { apiClient.listVaults(offset = 0, limit = 20) } returns
            ApiResult.Success(VaultPage(vaults, nextOffset = null, hasMore = false))

        vm.load()
        vm.loadMore()

        assertEquals(vaults, vm.state.value.vaults)
        coVerify(exactly = 0) { apiClient.listVaults(offset = 20, limit = 20) }
    }

    @Test
    fun `vault event updates the matching vault in place`() = runTest {
        val vaults = listOf(makeVault("v1"), makeVault("v2"))
        val events = MutableSharedFlow<VaultEvent>()
        every { vaultEventSocket.events("v1") } returns events
        every { vaultEventSocket.events("v2") } returns emptyFlow()
        coEvery { apiClient.listVaults(offset = 0, limit = 20) } returns
            ApiResult.Success(VaultPage(vaults, nextOffset = null, hasMore = false))

        vm.load()
        val updatedV1 = makeVault("v1").copy(balance = 999L)
        events.emit(VaultEvent(type = "deposit", vault = updatedV1))

        assertEquals(listOf(updatedV1, vaults[1]), vm.state.value.vaults)
    }

    @Test
    fun `checkIn success reloads vaults`() = runTest {
        val vaults = listOf(makeVault("v1"))
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Success(Unit)
        coEvery { apiClient.listVaults(offset = 0, limit = 20) } returns
            ApiResult.Success(VaultPage(vaults, nextOffset = null, hasMore = false))

        vm.checkIn("v1")

        coVerify { apiClient.checkIn("v1") }
        coVerify { apiClient.listVaults(offset = 0, limit = 20) }
    }

    @Test
    fun `checkIn network unavailable sets error`() = runTest {
        coEvery { apiClient.checkIn("v1") } returns ApiResult.NetworkUnavailable

        vm.checkIn("v1")

        assertNotNull(vm.state.value.error)
    }

    private fun makeVault(id: String) = Vault(
        id = id, owner = "GABC", beneficiary = "GXYZ",
        balance = 10_000_000L, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 172_800L,
        status = VaultStatus.active
    )
}

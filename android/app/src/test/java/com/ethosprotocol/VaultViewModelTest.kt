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
    fun `checkIn success reloads vaults`() = runTest {
        val vaults = listOf(makeVault("v1"))
        coEvery { apiClient.checkIn("v1") } returns ApiResult.Success(Unit)
        coEvery { apiClient.listVaults() } returns ApiResult.Success(vaults)

        vm.checkIn("v1")

        coVerify { apiClient.checkIn("v1") }
        coVerify { apiClient.listVaults() }
    }

    @Test
    fun `checkIn network unavailable sets error`() = runTest {
        coEvery { apiClient.checkIn("v1") } returns ApiResult.NetworkUnavailable

        vm.checkIn("v1")

        assertNotNull(vm.state.value.error)
    }

    // MARK: - #112 Pagination tests

    @Test
    fun `loadAll single page accumulates vaults and stops`() = runTest {
        val vaults = List(5) { makeVault("v$it") }
        val page = com.ethosprotocol.models.VaultPage(vaults = vaults, nextCursor = null, hasMore = false)
        coEvery { apiClient.listVaults(limit = 20, after = null) } returns ApiResult.Success(page)

        vm.loadAll()

        assertEquals(vaults, vm.state.value.vaults)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `loadAll multiple pages accumulates all vaults`() = runTest {
        val page1Vaults = List(20) { makeVault("p1-v$it") }
        val page2Vaults = List(7) { makeVault("p2-v$it") }
        val page1 = com.ethosprotocol.models.VaultPage(
            vaults = page1Vaults, nextCursor = "cursor-abc", hasMore = true
        )
        val page2 = com.ethosprotocol.models.VaultPage(
            vaults = page2Vaults, nextCursor = null, hasMore = false
        )
        coEvery { apiClient.listVaults(limit = 20, after = null) } returns ApiResult.Success(page1)
        coEvery { apiClient.listVaults(limit = 20, after = "cursor-abc") } returns ApiResult.Success(page2)

        vm.loadAll()

        val allVaults = vm.state.value.vaults
        assertEquals(27, allVaults.size)
        assertEquals(page1Vaults + page2Vaults, allVaults)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `loadAll network unavailable on first page sets offline flag`() = runTest {
        coEvery { apiClient.listVaults(limit = 20, after = null) } returns ApiResult.NetworkUnavailable

        vm.loadAll()

        assertTrue(vm.state.value.isOffline)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `loadAll network unavailable mid-pagination sets offline flag`() = runTest {
        val page1 = com.ethosprotocol.models.VaultPage(
            vaults = List(20) { makeVault("p1-v$it") }, nextCursor = "cursor-mid", hasMore = true
        )
        coEvery { apiClient.listVaults(limit = 20, after = null) } returns ApiResult.Success(page1)
        coEvery { apiClient.listVaults(limit = 20, after = "cursor-mid") } returns ApiResult.NetworkUnavailable

        vm.loadAll()

        assertTrue(vm.state.value.isOffline)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `loadAll large vault list fixture 100 vaults across 5 pages`() = runTest {
        // Shared large-vault-list fixture: 100 vaults in 5 pages of 20.
        val allVaults = List(100) { makeVault("large-v$it") }
        val pages = allVaults.chunked(20)
        for (i in pages.indices) {
            val cursor = if (i == 0) null else "cursor-${i - 1}"
            val nextCursor = if (i < pages.lastIndex) "cursor-$i" else null
            val hasMore = i < pages.lastIndex
            coEvery {
                apiClient.listVaults(limit = 20, after = cursor)
            } returns ApiResult.Success(
                com.ethosprotocol.models.VaultPage(vaults = pages[i], nextCursor = nextCursor, hasMore = hasMore)
            )
        }

        vm.loadAll()

        assertEquals(100, vm.state.value.vaults.size)
        assertEquals(allVaults, vm.state.value.vaults)
        assertFalse(vm.state.value.isLoading)
    }

    private fun makeVault(id: String) = Vault(
        id = id, owner = "GABC", beneficiary = "GXYZ",
        balance = 10_000_000L, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 172_800L,
        status = VaultStatus.active
    )
}

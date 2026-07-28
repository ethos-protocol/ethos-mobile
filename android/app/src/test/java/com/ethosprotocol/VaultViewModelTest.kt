package com.ethosprotocol

import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.VaultUiState
import com.ethosprotocol.ui.VaultViewModel
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingAction
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.PendingActionType
import android.content.Context
import io.mockk.*
import kotlinx.coroutines.CancellationException
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
    private val pendingActionDao: PendingActionDao = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private lateinit var vm: VaultViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(PendingActionSyncWorker.Companion)
        every { PendingActionSyncWorker.schedule(any()) } just Runs
        vm = VaultViewModel(apiClient, notificationHelper, pendingActionDao, context)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkObject(PendingActionSyncWorker.Companion)
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
    fun `load cancelled mid-request does not surface an error`() = runTest {
        // Simulates the screen (and viewModelScope) being torn down while
        // apiClient.listVaults() is in flight — the coroutine should stop silently
        // instead of writing a stray error/loading update to dead state.
        coEvery { apiClient.listVaults() } throws CancellationException("scope cancelled")

        vm.load()

        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
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
    fun `checkIn network unavailable queues a pending action and schedules sync`() = runTest {
        coEvery { apiClient.checkIn("v1") } returns ApiResult.NetworkUnavailable
        coEvery { pendingActionDao.getAll() } returns emptyList()

        vm.checkIn("v1")

        assertNotNull(vm.state.value.error)
        coVerify {
            pendingActionDao.insert(match {
                it.type == PendingActionType.CHECK_IN && it.vaultId == "v1" && it.dedupeKey == "check_in:v1"
            })
        }
        verify { PendingActionSyncWorker.schedule(context) }
    }

    @Test
    fun `createVault success reloads vaults`() = runTest {
        val vaults = listOf(makeVault("v1"))
        coEvery { apiClient.createVault(any()) } returns ApiResult.Success(makeVault("v1"))
        coEvery { apiClient.listVaults() } returns ApiResult.Success(vaults)

        vm.createVault("GXYZ", 30)

        coVerify { apiClient.createVault(any()) }
        coVerify { apiClient.listVaults() }
    }

    @Test
    fun `createVault network unavailable queues a pending action and schedules sync`() = runTest {
        coEvery { apiClient.createVault(any()) } returns ApiResult.NetworkUnavailable
        coEvery { pendingActionDao.getAll() } returns emptyList()

        vm.createVault("GXYZ", 30)

        assertNotNull(vm.state.value.error)
        coVerify {
            pendingActionDao.insert(match {
                it.type == PendingActionType.CREATE_VAULT && it.payloadJson != null
            })
        }
        verify { PendingActionSyncWorker.schedule(context) }
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

    @Test
    fun `deposit success updates only the target vault`() = runTest {
        val v1 = makeVault("v1")
        val v2 = makeVault("v2")
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(v1, v2))
        vm.load()

        val updatedV1 = v1.copy(balance = v1.balance + 1_000_000L)
        coEvery { apiClient.deposit("v1", 1_000_000L) } returns ApiResult.Success(updatedV1)

        vm.deposit("v1", 1_000_000L)

        coVerify { apiClient.deposit("v1", 1_000_000L) }
        assertEquals(listOf(updatedV1, v2), vm.state.value.vaults)
    }

    @Test
    fun `deposit error sets error message`() = runTest {
        coEvery { apiClient.deposit("v1", 1_000_000L) } returns ApiResult.Error("Server error", 500)

        vm.deposit("v1", 1_000_000L)

        assertEquals("Server error", vm.state.value.error)
    }

    @Test
    fun `deposit network unavailable sets error`() = runTest {
        coEvery { apiClient.deposit("v1", 1_000_000L) } returns ApiResult.NetworkUnavailable

        vm.deposit("v1", 1_000_000L)

        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `withdraw success updates only the target vault`() = runTest {
        val v1 = makeVault("v1")
        val v2 = makeVault("v2")
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(v1, v2))
        vm.load()

        val updatedV1 = v1.copy(balance = v1.balance - 1_000_000L)
        coEvery { apiClient.withdraw("v1", 1_000_000L) } returns ApiResult.Success(updatedV1)

        vm.withdraw("v1", 1_000_000L)

        coVerify { apiClient.withdraw("v1", 1_000_000L) }
        assertEquals(listOf(updatedV1, v2), vm.state.value.vaults)
    }

    @Test
    fun `withdraw error sets error message`() = runTest {
        coEvery { apiClient.withdraw("v1", 1_000_000L) } returns ApiResult.Error("Insufficient balance", 400)

        vm.withdraw("v1", 1_000_000L)

        assertEquals("Insufficient balance", vm.state.value.error)
    }

    @Test
    fun `withdraw network unavailable sets error`() = runTest {
        coEvery { apiClient.withdraw("v1", 1_000_000L) } returns ApiResult.NetworkUnavailable

        vm.withdraw("v1", 1_000_000L)

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

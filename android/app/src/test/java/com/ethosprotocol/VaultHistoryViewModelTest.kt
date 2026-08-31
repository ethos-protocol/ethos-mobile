package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.VaultHistoryEvent
import com.ethosprotocol.models.VaultHistoryPage
import com.ethosprotocol.ui.VaultHistoryViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Covers #217: vault activity history loading and cursor pagination. */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultHistoryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private lateinit var vm: VaultHistoryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        vm = VaultHistoryViewModel(apiClient)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeEvent(type: String) = VaultHistoryEvent(eventType = type, timestamp = "2026-04-01T00:00:00Z")

    @Test
    fun `load populates events and hasMore`() = runTest {
        val page = VaultHistoryPage(
            events = listOf(makeEvent("check_in"), makeEvent("deposit")),
            nextCursor = "cursor-1",
            hasMore = true
        )
        coEvery { apiClient.getVaultHistory("v1") } returns ApiResult.Success(page)

        vm.load("v1")

        assertEquals(2, vm.state.value.events.size)
        assertTrue(vm.state.value.hasMore)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `loadMore appends the next page and advances the cursor`() = runTest {
        val page1 = VaultHistoryPage(events = listOf(makeEvent("check_in")), nextCursor = "cursor-1", hasMore = true)
        val page2 = VaultHistoryPage(events = listOf(makeEvent("withdrawal")), nextCursor = null, hasMore = false)
        coEvery { apiClient.getVaultHistory("v1") } returns ApiResult.Success(page1)
        coEvery { apiClient.getVaultHistory("v1", after = "cursor-1") } returns ApiResult.Success(page2)

        vm.load("v1")
        vm.loadMore("v1")

        assertEquals(listOf("check_in", "withdrawal"), vm.state.value.events.map { it.eventType })
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun `loadMore does nothing when there is no next page`() = runTest {
        val page = VaultHistoryPage(events = listOf(makeEvent("check_in")), nextCursor = null, hasMore = false)
        coEvery { apiClient.getVaultHistory("v1") } returns ApiResult.Success(page)

        vm.load("v1")
        vm.loadMore("v1")

        coVerify(exactly = 0) { apiClient.getVaultHistory("v1", after = any()) }
        assertEquals(1, vm.state.value.events.size)
    }

    @Test
    fun `load network unavailable sets error`() = runTest {
        coEvery { apiClient.getVaultHistory("v1") } returns ApiResult.NetworkUnavailable

        vm.load("v1")

        assertEquals("No network", vm.state.value.error)
        assertTrue(vm.state.value.events.isEmpty())
    }
}

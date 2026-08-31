package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.Session
import com.ethosprotocol.ui.SessionsViewModel
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

/** Unit tests for [SessionsViewModel] (#208 — session/device list with remote sign-out). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private lateinit var vm: SessionsViewModel

    private val current = Session(
        id = "s1", deviceName = "Pixel 8", platform = "android",
        createdAt = "2026-01-01T00:00:00Z", lastActiveAt = "2026-01-02T00:00:00Z", isCurrent = true
    )
    private val other = Session(
        id = "s2", deviceName = "iPhone 15 Pro", platform = "ios",
        createdAt = "2026-01-01T00:00:00Z", lastActiveAt = "2026-01-01T12:00:00Z", isCurrent = false
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        vm = SessionsViewModel(apiClient)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load populates sessions on success`() = runTest {
        coEvery { apiClient.listSessions() } returns ApiResult.Success(listOf(current, other))

        vm.load()

        assertEquals(listOf(current, other), vm.state.value.sessions)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `load sets error on failure`() = runTest {
        coEvery { apiClient.listSessions() } returns ApiResult.Error("Server error", 500)

        vm.load()

        assertTrue(vm.state.value.sessions.isEmpty())
        assertEquals("Server error", vm.state.value.error)
    }

    @Test
    fun `revoke removes session from state on success`() = runTest {
        coEvery { apiClient.listSessions() } returns ApiResult.Success(listOf(current, other))
        coEvery { apiClient.revokeSession("s2") } returns ApiResult.Success(Unit)
        vm.load()

        vm.revoke(other)

        assertEquals(listOf(current), vm.state.value.sessions)
    }

    @Test
    fun `revoke keeps session and sets error on failure`() = runTest {
        coEvery { apiClient.listSessions() } returns ApiResult.Success(listOf(current, other))
        coEvery { apiClient.revokeSession("s2") } returns ApiResult.Error("Not found", 404)
        vm.load()

        vm.revoke(other)

        assertEquals(listOf(current, other), vm.state.value.sessions)
        assertEquals("Not found", vm.state.value.error)
    }

    @Test
    fun `revokeAllOthers reloads the session list on success`() = runTest {
        coEvery { apiClient.revokeOtherSessions() } returns ApiResult.Success(Unit)
        coEvery { apiClient.listSessions() } returns ApiResult.Success(listOf(current))

        vm.revokeAllOthers()

        coVerify { apiClient.listSessions() }
        assertEquals(listOf(current), vm.state.value.sessions)
    }

    @Test
    fun `revokeAllOthers sets error on failure without reloading`() = runTest {
        coEvery { apiClient.revokeOtherSessions() } returns ApiResult.Error("Server error", 500)

        vm.revokeAllOthers()

        coVerify(exactly = 0) { apiClient.listSessions() }
        assertEquals("Server error", vm.state.value.error)
    }
}

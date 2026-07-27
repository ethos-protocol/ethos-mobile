package com.ethosprotocol

import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.ui.AuthViewModel
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import android.app.Activity

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val passkeyService: PasskeyService = mockk()
    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val activity: Activity = mockk(relaxed = true)
    private lateinit var vm: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        vm = AuthViewModel(passkeyService, tokenProvider)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signIn success authenticates with no cooldown`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.success(Unit)

        vm.signIn(activity)

        assertTrue(vm.state.value.isAuthenticated)
        assertEquals(0, vm.state.value.cooldownRemainingSeconds)
    }

    @Test
    fun `signIn failure below threshold does not start cooldown`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))

        vm.signIn(activity)
        vm.signIn(activity)

        assertEquals(0, vm.state.value.cooldownRemainingSeconds)
        assertEquals("bad", vm.state.value.error)
    }

    @Test
    fun `signIn failure reaching threshold starts base cooldown`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))

        repeat(3) { vm.signIn(activity) }

        assertEquals(2, vm.state.value.cooldownRemainingSeconds)
    }

    @Test
    fun `cooldown escalates exponentially with further failures`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))

        repeat(4) { vm.signIn(activity) }
        assertEquals(4, vm.state.value.cooldownRemainingSeconds)

        repeat(1) { vm.signIn(activity) }
        assertEquals(8, vm.state.value.cooldownRemainingSeconds)
    }

    @Test
    fun `cooldown is clamped to max`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))

        repeat(10) { vm.signIn(activity) }

        assertEquals(60, vm.state.value.cooldownRemainingSeconds)
    }

    @Test
    fun `signIn is a no-op while cooldown is active`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))
        repeat(3) { vm.signIn(activity) }
        assertEquals(2, vm.state.value.cooldownRemainingSeconds)

        vm.signIn(activity)

        coVerify(exactly = 3) { passkeyService.authenticate(activity) }
    }

    @Test
    fun `successful signIn resets failure count and cooldown`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))
        repeat(2) { vm.signIn(activity) }
        assertEquals(0, vm.state.value.cooldownRemainingSeconds)

        coEvery { passkeyService.authenticate(activity) } returns Result.success(Unit)
        vm.signIn(activity)
        assertTrue(vm.state.value.isAuthenticated)

        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad again"))
        repeat(2) { vm.signIn(activity) }

        assertEquals(0, vm.state.value.cooldownRemainingSeconds)
        assertEquals("bad again", vm.state.value.error)
    }

    @Test
    fun `signOut clears cooldown state`() = runTest {
        coEvery { passkeyService.authenticate(activity) } returns Result.failure(RuntimeException("bad"))
        repeat(3) { vm.signIn(activity) }
        assertEquals(2, vm.state.value.cooldownRemainingSeconds)

        vm.signOut()

        assertEquals(0, vm.state.value.cooldownRemainingSeconds)
        assertFalse(vm.state.value.isAuthenticated)
    }
}

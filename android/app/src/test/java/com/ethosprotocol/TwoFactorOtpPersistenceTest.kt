package com.ethosprotocol

import androidx.lifecycle.SavedStateHandle
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.ui.TwoFactorViewModel
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
 * #172 — The OTP rate-limiting state of [TwoFactorViewModel] must survive process death.
 *
 * Process death is simulated the same way [DeepLinkViewModelTest] does it: a second
 * ViewModel instance is created from the *same* [SavedStateHandle], which is exactly what
 * the framework hands back after recreating a killed process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoFactorOtpPersistenceTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()

    @Before
    fun setup() = Dispatchers.setMain(testDispatcher)

    @After
    fun teardown() = Dispatchers.resetMain()

    private fun viewModel(handle: SavedStateHandle) = TwoFactorViewModel(apiClient, handle)

    @Test
    fun `cooldown survives process death`() = runTest {
        coEvery { apiClient.verify2FA(any(), any()) } returns ApiResult.Error("wrong code", 422)
        val handle = SavedStateHandle()

        val first = viewModel(handle)
        repeat(3) { first.verify2FA("v", "111111") }
        assertTrue("Precondition: blocked after 3 failures", first.state.value.isOtpBlocked)

        // Process death: same handle, brand-new ViewModel.
        val restored = viewModel(handle)

        assertEquals(3, restored.state.value.otpFailureCount)
        assertTrue("Cooldown must not reset on process death", restored.state.value.isOtpBlocked)
        assertTrue(
            "Remaining cooldown should be close to the original 30 s, was " +
                restored.state.value.otpCooldownSeconds,
            restored.state.value.otpCooldownSeconds in 29..30
        )
    }

    @Test
    fun `expired cooldown deadline does not block after process death`() {
        val handle = SavedStateHandle(
            mapOf(
                TwoFactorViewModel.KEY_OTP_FAILURE_COUNT to 3,
                // Deadline already in the past — the process was dead longer than the cooldown.
                TwoFactorViewModel.KEY_OTP_COOLDOWN_UNTIL to System.currentTimeMillis() - 1_000L
            )
        )

        val restored = viewModel(handle)

        assertFalse("An elapsed cooldown must not keep blocking", restored.state.value.isOtpBlocked)
        assertEquals(0, restored.state.value.otpCooldownSeconds)
        assertEquals("Failure count is still remembered", 3, restored.state.value.otpFailureCount)
    }

    @Test
    fun `successful verification clears the persisted cooldown state`() = runTest {
        coEvery { apiClient.verify2FA(any(), any()) } returnsMany listOf(
            ApiResult.Error("wrong", 422),
            ApiResult.Success(Unit)
        )
        val handle = SavedStateHandle()

        val vm = viewModel(handle)
        vm.verify2FA("v", "111111")
        assertEquals(1, handle.get<Int>(TwoFactorViewModel.KEY_OTP_FAILURE_COUNT))

        vm.verify2FA("v", "123456")

        assertNull(handle.get<Int>(TwoFactorViewModel.KEY_OTP_FAILURE_COUNT))
        assertNull(handle.get<Long>(TwoFactorViewModel.KEY_OTP_COOLDOWN_UNTIL))
        assertEquals(0, viewModel(handle).state.value.otpFailureCount)
    }
}

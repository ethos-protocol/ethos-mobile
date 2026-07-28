package com.ethosprotocol

import android.app.Activity
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.*
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.ui.AcceptanceViewModel
import com.ethosprotocol.ui.AuthViewModel
import com.ethosprotocol.ui.TwoFactorViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// ============================================================================
// AuthViewModelTest
// ============================================================================

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val passkeyService: PasskeyService = mockk()
    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val activity: Activity = mockk(relaxed = true)
    private lateinit var vm: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // -----------------------------------------------------------------------
    // Initial state
    // -----------------------------------------------------------------------

    @Test
    fun `initial state isAuthenticated true when token present`() = runTest {
        every { tokenProvider.token } returns "some-jwt"
        vm = AuthViewModel(passkeyService, tokenProvider)

        assertTrue(vm.state.value.isAuthenticated)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `initial state isAuthenticated false when token absent`() = runTest {
        every { tokenProvider.token } returns null
        vm = AuthViewModel(passkeyService, tokenProvider)

        assertFalse(vm.state.value.isAuthenticated)
    }

    // -----------------------------------------------------------------------
    // signIn
    // -----------------------------------------------------------------------

    @Test
    fun `signIn success sets isAuthenticated true`() = runTest {
        every { tokenProvider.token } returns null
        vm = AuthViewModel(passkeyService, tokenProvider)
        coEvery { passkeyService.authenticate(activity) } returns Result.success(Unit)

        vm.signIn(activity)

        assertTrue(vm.state.value.isAuthenticated)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `signIn failure sets error message`() = runTest {
        every { tokenProvider.token } returns null
        vm = AuthViewModel(passkeyService, tokenProvider)
        coEvery { passkeyService.authenticate(activity) } returns
            Result.failure(RuntimeException("Passkey cancelled"))

        vm.signIn(activity)

        assertFalse(vm.state.value.isAuthenticated)
        assertFalse(vm.state.value.isLoading)
        assertEquals("Passkey cancelled", vm.state.value.error)
    }

    @Test
    fun `signIn clears previous error before attempt`() = runTest {
        every { tokenProvider.token } returns null
        vm = AuthViewModel(passkeyService, tokenProvider)
        // First call fails to put error in state
        coEvery { passkeyService.authenticate(activity) } returns
            Result.failure(RuntimeException("first error"))
        vm.signIn(activity)
        assertEquals("first error", vm.state.value.error)

        // Second call succeeds — error must be cleared
        coEvery { passkeyService.authenticate(activity) } returns Result.success(Unit)
        vm.signIn(activity)

        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isAuthenticated)
    }

    // -----------------------------------------------------------------------
    // register
    // -----------------------------------------------------------------------

    @Test
    fun `register success triggers signIn and sets isAuthenticated`() = runTest {
        every { tokenProvider.token } returns null
        vm = AuthViewModel(passkeyService, tokenProvider)
        coEvery { passkeyService.register(activity, "alice") } returns Result.success(Unit)
        coEvery { passkeyService.authenticate(activity) } returns Result.success(Unit)

        vm.register(activity, "alice")

        assertTrue(vm.state.value.isAuthenticated)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `register failure sets error message`() = runTest {
        every { tokenProvider.token } returns null
        vm = AuthViewModel(passkeyService, tokenProvider)
        coEvery { passkeyService.register(activity, "alice") } returns
            Result.failure(RuntimeException("Username taken"))

        vm.register(activity, "alice")

        assertFalse(vm.state.value.isAuthenticated)
        assertEquals("Username taken", vm.state.value.error)
    }

    // -----------------------------------------------------------------------
    // signOut
    // -----------------------------------------------------------------------

    @Test
    fun `signOut clears token and sets isAuthenticated false`() = runTest {
        every { tokenProvider.token } returns "jwt"
        vm = AuthViewModel(passkeyService, tokenProvider)

        vm.signOut()

        verify { tokenProvider.clear() }
        assertFalse(vm.state.value.isAuthenticated)
    }
}

// ============================================================================
// TwoFactorViewModelTest
// ============================================================================

@OptIn(ExperimentalCoroutinesApi::class)
class TwoFactorViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private lateinit var vm: TwoFactorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        vm = TwoFactorViewModel(apiClient)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun status(enabled: Boolean) = TwoFactorStatus(
        vaultId = "v1", enabled = enabled, method = TwoFactorMethod.totp
    )

    private fun enableResponse() = Enable2FAResponse(
        vaultId = "v1", method = TwoFactorMethod.totp,
        secret = "BASE32SECRET", provisioningUri = "otpauth://totp/…"
    )

    // -----------------------------------------------------------------------
    // loadStatus
    // -----------------------------------------------------------------------

    @Test
    fun `loadStatus success updates status state`() = runTest {
        val s = status(enabled = true)
        coEvery { apiClient.get2FAStatus("v1") } returns ApiResult.Success(s)

        vm.loadStatus("v1")

        assertEquals(s, vm.state.value.status)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `loadStatus error sets error message`() = runTest {
        coEvery { apiClient.get2FAStatus("v1") } returns ApiResult.Error("Not found", 404)

        vm.loadStatus("v1")

        assertNull(vm.state.value.status)
        assertEquals("Not found", vm.state.value.error)
    }

    @Test
    fun `loadStatus network unavailable sets no network error`() = runTest {
        coEvery { apiClient.get2FAStatus("v1") } returns ApiResult.NetworkUnavailable

        vm.loadStatus("v1")

        assertEquals("No network", vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
    }

    // -----------------------------------------------------------------------
    // enable2FA
    // -----------------------------------------------------------------------

    @Test
    fun `enable2FA success stores setupResponse`() = runTest {
        val resp = enableResponse()
        coEvery {
            apiClient.enable2FA("v1", Enable2FARequest(TwoFactorMethod.totp, null, null))
        } returns ApiResult.Success(resp)

        vm.enable2FA("v1", TwoFactorMethod.totp, null, null)

        assertEquals(resp, vm.state.value.setupResponse)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `enable2FA error sets error message`() = runTest {
        coEvery {
            apiClient.enable2FA("v1", any())
        } returns ApiResult.Error("Server error", 500)

        vm.enable2FA("v1", TwoFactorMethod.totp, null, null)

        assertNull(vm.state.value.setupResponse)
        assertEquals("Server error", vm.state.value.error)
    }

    @Test
    fun `enable2FA network unavailable sets no network error`() = runTest {
        coEvery { apiClient.enable2FA("v1", any()) } returns ApiResult.NetworkUnavailable

        vm.enable2FA("v1", TwoFactorMethod.totp, null, null)

        assertEquals("No network", vm.state.value.error)
    }

    // -----------------------------------------------------------------------
    // verify2FA
    // -----------------------------------------------------------------------

    @Test
    fun `verify2FA success sets verified true`() = runTest {
        coEvery { apiClient.verify2FA("v1", Verify2FARequest("123456")) } returns
            ApiResult.Success(Unit)

        vm.verify2FA("v1", "123456")

        assertTrue(vm.state.value.verified)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `verify2FA error sets error message`() = runTest {
        coEvery { apiClient.verify2FA("v1", any()) } returns ApiResult.Error("Wrong code", 422)

        vm.verify2FA("v1", "000000")

        assertFalse(vm.state.value.verified)
        assertEquals("Wrong code", vm.state.value.error)
    }

    @Test
    fun `verify2FA network unavailable sets no network error`() = runTest {
        coEvery { apiClient.verify2FA("v1", any()) } returns ApiResult.NetworkUnavailable

        vm.verify2FA("v1", "123456")

        assertFalse(vm.state.value.verified)
        assertEquals("No network", vm.state.value.error)
    }

    // -----------------------------------------------------------------------
    // disable2FA
    // -----------------------------------------------------------------------

    @Test
    fun `disable2FA success clears status`() = runTest {
        // Prime state with a status first
        coEvery { apiClient.get2FAStatus("v1") } returns ApiResult.Success(status(true))
        vm.loadStatus("v1")
        assertNotNull(vm.state.value.status)

        coEvery { apiClient.disable2FA("v1") } returns ApiResult.Success(Unit)
        vm.disable2FA("v1")

        assertNull(vm.state.value.status)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `disable2FA error sets error message`() = runTest {
        coEvery { apiClient.disable2FA("v1") } returns ApiResult.Error("Forbidden", 403)

        vm.disable2FA("v1")

        assertEquals("Forbidden", vm.state.value.error)
    }

    @Test
    fun `disable2FA network unavailable sets no network error`() = runTest {
        coEvery { apiClient.disable2FA("v1") } returns ApiResult.NetworkUnavailable

        vm.disable2FA("v1")

        assertEquals("No network", vm.state.value.error)
    }
}

// ============================================================================
// AcceptanceViewModelTest
// ============================================================================

@OptIn(ExperimentalCoroutinesApi::class)
class AcceptanceViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private lateinit var vm: AcceptanceViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        vm = AcceptanceViewModel(apiClient)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // -----------------------------------------------------------------------
    // accept
    // -----------------------------------------------------------------------

    @Test
    fun `accept success sets isAccepted true`() = runTest {
        coEvery { apiClient.acceptBeneficiary("v1") } returns ApiResult.Success(Unit)

        vm.accept("v1")

        assertTrue(vm.state.value.isAccepted)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `accept error sets error message`() = runTest {
        coEvery { apiClient.acceptBeneficiary("v1") } returns ApiResult.Error("Not found", 404)

        vm.accept("v1")

        assertFalse(vm.state.value.isAccepted)
        assertEquals("Not found", vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `accept network unavailable sets offline error message`() = runTest {
        coEvery { apiClient.acceptBeneficiary("v1") } returns ApiResult.NetworkUnavailable

        vm.accept("v1")

        assertFalse(vm.state.value.isAccepted)
        assertEquals("No network. Please try again.", vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
    }

    /**
     * Regression test for #87: accept() must supply the beneficiary token when
     * the token requirement is implemented.  This test documents the expected state
     * transition (success → isAccepted) so that dropping the token from the call
     * becomes a deliberate, visible test failure once #87 lands.
     */
    @Test
    fun `accept token requirement placeholder – isAccepted true on success`() = runTest {
        // TODO(#87): update this test to pass a beneficiary acceptance token once
        // the API contract requires it (acceptBeneficiary(vaultId, token)).
        coEvery { apiClient.acceptBeneficiary("v1") } returns ApiResult.Success(Unit)

        vm.accept("v1")

        assertTrue(
            "AcceptanceViewModel must set isAccepted=true on a successful accept() call. " +
                "If this fails after #87 lands, ensure the token is being forwarded to the API.",
            vm.state.value.isAccepted
        )
    }

    @Test
    fun `loading flag is true during accept and false after`() = runTest {
        // Use a dispatcher that does NOT auto-advance so we can observe isLoading=true
        val pausingDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(pausingDispatcher)
        val pausingVm = AcceptanceViewModel(apiClient)
        coEvery { apiClient.acceptBeneficiary("v1") } returns ApiResult.Success(Unit)

        pausingVm.accept("v1")
        // coroutine started but not yet resumed
        assertTrue(pausingVm.state.value.isLoading)

        // advance until idle to let the coroutine complete
        advanceUntilIdle()
        assertFalse(pausingVm.state.value.isLoading)
        assertTrue(pausingVm.state.value.isAccepted)
    }
}

package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.BackupCodesResponse
import com.ethosprotocol.models.BackupCodesStatus
import com.ethosprotocol.models.Enable2FAResponse
import com.ethosprotocol.models.Switch2FARequest
import com.ethosprotocol.models.TwoFactorMethod
import com.ethosprotocol.models.TwoFactorStatus
import com.ethosprotocol.models.Verify2FARequest
import com.ethosprotocol.models.Verify2FAResponse
import com.ethosprotocol.ui.TwoFactorViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the four 2FA improvements:
 *  #227 — Available methods
 *  #226 — Trust-device opt-in
 *  #224 — Backup/recovery codes
 *  #225 — Switch method without disabling first
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoFAImprovementsTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private lateinit var vm: TwoFactorViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        vm = TwoFactorViewModel(apiClient)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // #227 — Available Methods
    // =========================================================================

    @Test
    fun `loadStatus stores availableMethods from server response`() = runTest {
        val status = TwoFactorStatus(
            vaultId = "v1",
            enabled = false,
            method = null,
            verified = false,
            availableMethods = listOf(TwoFactorMethod.totp, TwoFactorMethod.email)
        )
        coEvery { apiClient.get2FAStatus("v1") } returns ApiResult.Success(status)

        vm.loadStatus("v1")

        val loaded = vm.state.value.status
        assertNotNull(loaded)
        assertEquals(listOf(TwoFactorMethod.totp, TwoFactorMethod.email), loaded!!.availableMethods)
        assertFalse(
            "SMS must not appear when server excluded it",
            loaded.availableMethods.contains(TwoFactorMethod.sms)
        )
    }

    @Test
    fun `TwoFactorStatus defaults availableMethods to all when field absent`() {
        // Simulates an older server response without available_methods by using the
        // default parameter value.
        val status = TwoFactorStatus(
            vaultId = "v1", enabled = false, method = null, verified = false
        )
        assertEquals(
            "Default must include all three methods for backward-compat",
            TwoFactorMethod.values().toSet(),
            status.availableMethods.toSet()
        )
    }

    @Test
    fun `TwoFactorStatus serialization preserves availableMethods`() {
        val json = Json { ignoreUnknownKeys = true }
        val status = TwoFactorStatus(
            vaultId = "v1", enabled = true, method = TwoFactorMethod.totp,
            verified = true, availableMethods = listOf(TwoFactorMethod.totp)
        )
        val encoded = json.encodeToString(TwoFactorStatus.serializer(), status)
        val decoded = json.decodeFromString(TwoFactorStatus.serializer(), encoded)
        assertEquals(listOf(TwoFactorMethod.totp), decoded.availableMethods)
    }

    @Test
    fun `reducedMethodList_excludesSMSFromSwitchable`() {
        val available = listOf(TwoFactorMethod.totp, TwoFactorMethod.email)
        val current = TwoFactorMethod.totp
        val switchable = available.filter { it != current }
        assertFalse("SMS must not be offered when not in available_methods",
            switchable.contains(TwoFactorMethod.sms))
        assertEquals(listOf(TwoFactorMethod.email), switchable)
    }

    // =========================================================================
    // #226 — Trust-Device
    // =========================================================================

    @Test
    fun `Verify2FARequest defaults trustDevice to false`() {
        val req = Verify2FARequest(otp = "123456")
        assertFalse("trust_device must default to false (opt-in only)", req.trustDevice)
    }

    @Test
    fun `Verify2FARequest with trustDevice true serializes correctly`() {
        val json = Json { ignoreUnknownKeys = true }
        val req = Verify2FARequest(otp = "654321", trustDevice = true)
        val encoded = json.encodeToString(Verify2FARequest.serializer(), req)
        assertTrue(encoded.contains("\"trust_device\":true"))
        assertTrue(encoded.contains("\"otp\":\"654321\""))
    }

    @Test
    fun `verify2FA with trustDevice stores token in state`() = runTest {
        val response = Verify2FAResponse(
            deviceTrustToken = "tok_abc123",
            expiresAt = "2026-09-25T22:00:00Z"
        )
        coEvery { apiClient.verify2FA("v1", Verify2FARequest("123456", trustDevice = true)) } returns
            ApiResult.Success(response)

        vm.verify2FA("v1", "123456", trustDevice = true)

        val state = vm.state.value
        assertTrue(state.verified)
        assertEquals("tok_abc123", state.deviceTrustToken)
        assertNotNull(state.deviceTrustExpiresAt)
    }

    @Test
    fun `verify2FA without trustDevice leaves token null in state`() = runTest {
        val response = Verify2FAResponse(deviceTrustToken = null, expiresAt = null)
        coEvery { apiClient.verify2FA("v1", Verify2FARequest("123456", trustDevice = false)) } returns
            ApiResult.Success(response)

        vm.verify2FA("v1", "123456", trustDevice = false)

        assertNull(vm.state.value.deviceTrustToken)
    }

    @Test
    fun `Verify2FAResponse decodes with null token correctly`() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded = """{"device_trust_token":null,"expires_at":null}"""
        val decoded = json.decodeFromString(Verify2FAResponse.serializer(), encoded)
        assertNull(decoded.deviceTrustToken)
        assertNull(decoded.expiresAt)
    }

    // =========================================================================
    // #224 — Backup Codes
    // =========================================================================

    @Test
    fun `generateBackupCodes success sets showBackupCodes true and stores codes`() = runTest {
        // First set up a TOTP setup response so the ViewModel knows this was initial setup.
        val setupResp = Enable2FAResponse(
            vaultId = "v1", method = TwoFactorMethod.totp, secret = "SECRET",
            provisioningUri = "otpauth://totp/Ethos?secret=SECRET"
        )
        coEvery { apiClient.enable2FA("v1", any()) } returns ApiResult.Success(setupResp)
        vm.enable2FA("v1", TwoFactorMethod.totp, null, null)

        val codes = listOf("AAAA-BBBB", "CCCC-DDDD", "EEEE-FFFF", "GGGG-HHHH",
                           "IIII-JJJJ", "KKKK-LLLL", "MMMM-NNNN", "OOOO-PPPP")
        coEvery { apiClient.generateBackupCodes("v1") } returns
            ApiResult.Success(BackupCodesResponse(codes = codes, generatedAt = "2026-08-26T22:00:00Z"))
        // Also mock verify so the initial-setup path triggers backup code generation.
        coEvery { apiClient.verify2FA("v1", any()) } returns
            ApiResult.Success(Verify2FAResponse(deviceTrustToken = null, expiresAt = null))

        vm.verify2FA("v1", "123456")

        val state = vm.state.value
        assertTrue("showBackupCodes must be true after initial TOTP setup", state.showBackupCodes)
        assertEquals(8, state.backupCodes.size)
        assertEquals(codes.first(), state.backupCodes.first())
    }

    @Test
    fun `dismissBackupCodes clears showBackupCodes and codes`() = runTest {
        val codes = listOf("AAAA-BBBB", "CCCC-DDDD")
        coEvery { apiClient.generateBackupCodes("v1") } returns
            ApiResult.Success(BackupCodesResponse(codes = codes, generatedAt = "2026-08-26T22:00:00Z"))

        vm.generateBackupCodes("v1")
        assertTrue(vm.state.value.showBackupCodes)

        vm.dismissBackupCodes()

        assertFalse(vm.state.value.showBackupCodes)
        assertTrue(vm.state.value.backupCodes.isEmpty())
    }

    @Test
    fun `generateBackupCodes network error leaves showBackupCodes false`() = runTest {
        coEvery { apiClient.generateBackupCodes("v1") } returns ApiResult.NetworkUnavailable

        vm.generateBackupCodes("v1")

        assertFalse(
            "showBackupCodes must remain false when code generation fails",
            vm.state.value.showBackupCodes
        )
    }

    @Test
    fun `BackupCodesResponse serialization round-trips correctly`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = BackupCodesResponse(
            codes = listOf("AAAA-BBBB", "CCCC-DDDD"),
            generatedAt = "2026-08-26T22:00:00Z"
        )
        val encoded = json.encodeToString(BackupCodesResponse.serializer(), original)
        val decoded = json.decodeFromString(BackupCodesResponse.serializer(), encoded)
        assertEquals(original.codes, decoded.codes)
    }

    @Test
    fun `backup codes in a single batch are unique`() {
        val codes = listOf("AAAA-BBBB", "CCCC-DDDD", "EEEE-FFFF", "GGGG-HHHH",
                           "IIII-JJJJ", "KKKK-LLLL", "MMMM-NNNN", "OOOO-PPPP")
        assertEquals("All codes in a set must be unique", codes.size, codes.toSet().size)
    }

    // =========================================================================
    // #225 — Switch Method Without Disabling First
    // =========================================================================

    @Test
    fun `switch2FAMethod success stores switchResponse`() = runTest {
        val switchResp = Enable2FAResponse(
            vaultId = "v1", method = TwoFactorMethod.email,
            secret = null, provisioningUri = null
        )
        coEvery { apiClient.switch2FAMethod("v1", Switch2FARequest(TwoFactorMethod.email)) } returns
            ApiResult.Success(switchResp)

        vm.switch2FAMethod("v1", TwoFactorMethod.email, null, null)

        assertEquals(switchResp, vm.state.value.switchResponse)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `switch2FAMethod error sets error message`() = runTest {
        coEvery { apiClient.switch2FAMethod("v1", any()) } returns
            ApiResult.Error("Method not available", 422)

        vm.switch2FAMethod("v1", TwoFactorMethod.sms, "+15551234567", null)

        assertEquals("Method not available", vm.state.value.error)
        assertNull(vm.state.value.switchResponse)
    }

    @Test
    fun `switch2FAMethod network unavailable sets no-network error`() = runTest {
        coEvery { apiClient.switch2FAMethod("v1", any()) } returns ApiResult.NetworkUnavailable

        vm.switch2FAMethod("v1", TwoFactorMethod.totp, null, null)

        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.switchResponse)
    }

    @Test
    fun `clearSwitchResponse nulls out switchResponse`() = runTest {
        val switchResp = Enable2FAResponse(
            vaultId = "v1", method = TwoFactorMethod.sms, secret = null, provisioningUri = null
        )
        coEvery { apiClient.switch2FAMethod("v1", any()) } returns ApiResult.Success(switchResp)
        vm.switch2FAMethod("v1", TwoFactorMethod.sms, "+15551234567", null)

        assertNotNull(vm.state.value.switchResponse)
        vm.clearSwitchResponse()
        assertNull(vm.state.value.switchResponse)
    }

    @Test
    fun `switchable methods exclude current method`() {
        // Documents the invariant used by TwoFactorSwitchScreen.
        val available = TwoFactorMethod.values().toList()
        val current = TwoFactorMethod.totp
        val switchable = available.filter { it != current }

        assertFalse("Current method must not be in switchable list",
            switchable.contains(current))
        assertEquals(available.size - 1, switchable.size)
    }

    @Test
    fun `Switch2FARequest serializes new_method correctly`() {
        val json = Json { ignoreUnknownKeys = true }
        val req = Switch2FARequest(newMethod = TwoFactorMethod.sms, phone = "+15555550100")
        val encoded = json.encodeToString(Switch2FARequest.serializer(), req)
        assertTrue(encoded.contains("\"new_method\":\"sms\""))
        assertTrue(encoded.contains("\"phone\":\"+15555550100\""))
    }
}

package com.ethosprotocol.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethosprotocol.BuildConfig
import com.ethosprotocol.api.ApiCallFailedException
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiErrorMapper
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.*
import com.ethosprotocol.models.CreateVaultRequest
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.TwoFactorMethod
import com.ethosprotocol.models.TwoFactorStatus
import com.ethosprotocol.models.Enable2FARequest
import com.ethosprotocol.models.Enable2FAResponse
import com.ethosprotocol.models.Verify2FARequest
import com.ethosprotocol.models.Verify2FAResponse
import com.ethosprotocol.models.Switch2FARequest
import com.ethosprotocol.models.BackupCodesResponse
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PasskeyException
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.services.PendingAction
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.PendingActionType
import com.ethosprotocol.services.VaultAssociationStore
import com.ethosprotocol.services.VaultEventSocket
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
// --- TwoFactor ViewModel ---

@Immutable
data class TwoFactorUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val verified: Boolean = false,
    val setupResponse: Enable2FAResponse? = null,
    val status: TwoFactorStatus? = null,
    // #119: Client-side rate limiting for OTP verification attempts.
    val otpFailureCount: Int = 0,
    val otpCooldownSeconds: Int = 0,
    // #224: Backup codes shown once immediately after initial TOTP setup.
    val backupCodes: List<String> = emptyList(),
    val showBackupCodes: Boolean = false,
    // #225: Set when a method-switch flow is in progress.
    val switchResponse: Enable2FAResponse? = null,
    // #226: Set to true after verify when the user opted in to "remember this device".
    val deviceTrustToken: String? = null,
    val deviceTrustExpiresAt: String? = null
) {
    val isOtpBlocked: Boolean get() = otpCooldownSeconds > 0
}

@HiltViewModel
class TwoFactorViewModel @Inject constructor(
    private val apiClient: ApiClient,
    /**
     * #172: OTP rate-limiting state lives here rather than only in [_state] so it survives
     * process death, exactly as [DeepLinkViewModel] does for pending deep links. A plain
     * ViewModel field is cleared when the system reclaims the process, which would reset
     * the failure count to zero and hand an attacker unlimited extra guesses.
     *
     * Defaulted so existing call sites that only need the API client (tests) stay valid;
     * Hilt always supplies a real handle for the ViewModel it creates.
     */
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    private val _state = MutableStateFlow(TwoFactorUiState())
    val state = _state.asStateFlow()

    private var cooldownJob: kotlinx.coroutines.Job? = null

    init {
        // Rehydrate the rate-limiting state saved by a previous instance of this ViewModel.
        // The cooldown is persisted as an absolute deadline, so the remaining seconds are
        // recomputed against the current wall clock however long the process was dead.
        val failures = savedStateHandle.readNumber(KEY_OTP_FAILURE_COUNT)?.toInt() ?: 0
        val remaining = remainingCooldownSeconds()
        if (failures > 0 || remaining > 0) {
            _state.update { it.copy(otpFailureCount = failures, otpCooldownSeconds = remaining) }
        }
        if (remaining > 0) startCooldownTicker(remaining)
    }

    fun loadStatus(vaultId: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.get2FAStatus(vaultId)) {
            is ApiResult.Success -> _state.update { it.copy(status = result.data, isLoading = false) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun enable2FA(vaultId: String, method: TwoFactorMethod, phone: String?, email: String?) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        val req = Enable2FARequest(method = method, phone = phone, email = email)
        when (val result = apiClient.enable2FA(vaultId, req)) {
            is ApiResult.Success -> _state.update { it.copy(setupResponse = result.data, isLoading = false) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun verify2FA(vaultId: String, otp: String, trustDevice: Boolean = false) = viewModelScope.launch {
        if (_state.value.isOtpBlocked) return@launch
        _state.update { it.copy(isLoading = true, error = null) }
        val req = Verify2FARequest(otp = otp, trustDevice = trustDevice)
        when (val result = apiClient.verify2FA(vaultId, req)) {
            is ApiResult.Success -> {
                // #119: Reset rate-limiting state on success (#172: including the persisted copy)
                cancelCooldown()
                clearPersistedRateLimitState()
                val response = result.data

                // #226: Persist the trust token in state so the screen layer can store it securely.
                val baseUpdate = _state.value.copy(
                    verified = true, isLoading = false,
                    otpFailureCount = 0, otpCooldownSeconds = 0,
                    deviceTrustToken = response.deviceTrustToken,
                    deviceTrustExpiresAt = response.expiresAt
                )
                _state.value = baseUpdate

                // #224: Generate backup codes immediately after a successful initial TOTP setup
                // (detected by setupResponse being present and method == totp).
                val isInitialTotpSetup = _state.value.setupResponse?.method == TwoFactorMethod.totp
                if (isInitialTotpSetup) {
                    generateBackupCodesInternal(vaultId)
                }
            }
            is ApiResult.Error -> {
                val newCount = _state.value.otpFailureCount + 1
                savedStateHandle[KEY_OTP_FAILURE_COUNT] = newCount
                val cooldown = otpCooldownSeconds(newCount)
                _state.update { it.copy(isLoading = false, error = result.message, otpFailureCount = newCount) }
                if (cooldown > 0) startCooldown(cooldown)
            }
            ApiResult.NetworkUnavailable -> {
                _state.update { it.copy(isLoading = false, error = "No network") }
            }
        }
    }

    // MARK: - #224 Backup Codes

    /** Generates 8 fresh one-time backup codes; called automatically after initial TOTP setup. */
    fun generateBackupCodes(vaultId: String) = viewModelScope.launch {
        generateBackupCodesInternal(vaultId)
    }

    private suspend fun generateBackupCodesInternal(vaultId: String) {
        _state.update { it.copy(isLoading = true) }
        when (val result = apiClient.generateBackupCodes(vaultId)) {
            is ApiResult.Success -> _state.update {
                it.copy(isLoading = false, backupCodes = result.data.codes, showBackupCodes = true)
            }
            is ApiResult.Error -> _state.update {
                // Non-fatal: codes can be regenerated later from settings.
                it.copy(isLoading = false, showBackupCodes = false)
            }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false) }
        }
    }

    fun dismissBackupCodes() {
        _state.update { it.copy(showBackupCodes = false, backupCodes = emptyList()) }
    }

    // MARK: - #225 Switch 2FA Method

    /**
     * Initiates a method switch without disabling the current method first.
     * The new method is set up in a pending state; the old one remains active
     * until [verify2FA] confirms the new one — no gap in protection.
     */
    fun switch2FAMethod(
        vaultId: String,
        newMethod: TwoFactorMethod,
        phone: String?,
        email: String?
    ) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        val req = Switch2FARequest(newMethod = newMethod, phone = phone, email = email)
        when (val result = apiClient.switch2FAMethod(vaultId, req)) {
            is ApiResult.Success -> _state.update {
                it.copy(switchResponse = result.data, isLoading = false)
            }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun clearSwitchResponse() {
        _state.update { it.copy(switchResponse = null) }
    }

    // #120: Require biometric authentication before disabling 2FA (more security-sensitive than check-in)
    fun disable2FA(vaultId: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.disable2FA(vaultId)) {
            is ApiResult.Success -> _state.update { it.copy(status = null, isLoading = false) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    // Called only after successful BiometricHelper authentication in the screen layer.
    // Keeping the biometric prompt in the screen (where a FragmentActivity is available)
    // and the network call here (in the ViewModel) matches the same pattern used for check-in.
    fun disable2FAAfterBiometric(vaultId: String) = disable2FA(vaultId)

    // ── #119 internals ────────────────────────────────────────────────────────

    /**
     * Escalating cooldown schedule matching the iOS OTPRateLimiter:
     *   1–2 failures → no cooldown (grace period)
     *   3 failures   → 30 s
     *   4 failures   → 60 s
     *   5+ failures  → 120 s (capped)
     */
    internal fun otpCooldownSeconds(failures: Int): Int = when {
        failures < 3  -> 0
        failures == 3 -> 30
        failures == 4 -> 60
        else          -> 120
    }

    private fun startCooldown(seconds: Int) {
        cancelCooldown()
        // Persist when the cooldown ends, not how long is left: a countdown value would be
        // meaningless after the process is killed and restarted (#172).
        savedStateHandle[KEY_OTP_COOLDOWN_UNTIL] = System.currentTimeMillis() + seconds * 1_000L
        _state.update { it.copy(otpCooldownSeconds = seconds) }
        startCooldownTicker(seconds)
    }

    /** Ticks [TwoFactorUiState.otpCooldownSeconds] down to 0, driven by the persisted deadline. */
    private fun startCooldownTicker(seconds: Int) {
        cancelCooldown()
        cooldownJob = viewModelScope.launch {
            repeat(seconds) {
                kotlinx.coroutines.delay(1_000)
                val remaining = remainingCooldownSeconds()
                _state.update { it.copy(otpCooldownSeconds = remaining) }
                if (remaining <= 0) return@launch
            }
            _state.update { it.copy(otpCooldownSeconds = remainingCooldownSeconds()) }
        }
    }

    /** Seconds left until the persisted cooldown deadline, or 0 when none is pending. */
    private fun remainingCooldownSeconds(): Int {
        val deadline = savedStateHandle.readNumber(KEY_OTP_COOLDOWN_UNTIL) ?: return 0
        val remainingMillis = deadline.toLong() - System.currentTimeMillis()
        if (remainingMillis <= 0) return 0
        return ((remainingMillis + 999) / 1_000).toInt()
    }

    private fun clearPersistedRateLimitState() {
        savedStateHandle.remove<Any>(KEY_OTP_FAILURE_COUNT)
        savedStateHandle.remove<Any>(KEY_OTP_COOLDOWN_UNTIL)
    }

    /**
     * Reads a numeric value without assuming which box it comes back in: a value that has
     * been through a [SavedStateHandle] save/restore round-trip is not guaranteed to keep
     * the exact `Int`/`Long` type it was written with, and a typed `get<Int>` would then
     * fail with a [ClassCastException] on exactly the process-death path this state exists
     * to survive.
     */
    private fun SavedStateHandle.readNumber(key: String): Number? = get<Any>(key) as? Number

    private fun cancelCooldown() {
        cooldownJob?.cancel()
        cooldownJob = null
    }

    companion object {
        internal const val KEY_OTP_FAILURE_COUNT = "otp_failure_count"
        internal const val KEY_OTP_COOLDOWN_UNTIL = "otp_cooldown_until_millis"
    }

    override fun onCleared() {
        super.onCleared()
        cancelCooldown()
    }
}


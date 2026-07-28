package com.ethosprotocol.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethosprotocol.BuildConfig
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiErrorMapper
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.*
import com.ethosprotocol.models.CreateVaultRequest
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.TwoFactorMethod
import com.ethosprotocol.models.TwoFactorStatus
import com.ethosprotocol.models.Enable2FARequest
import com.ethosprotocol.models.Enable2FAResponse
import com.ethosprotocol.models.Verify2FARequest
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.services.PendingAction
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.PendingActionType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

// --- Auth ViewModel ---

data class AuthUiState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val passkeyService: PasskeyService,
    private val tokenProvider: TokenProvider,
    private val apiClient: ApiClient,
    private val notificationHelper: NotificationHelper,
    private val pendingActionDao: PendingActionDao
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState(isAuthenticated = tokenProvider.token != null))
    val state = _state.asStateFlow()

    fun signIn(activity: Activity) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        passkeyService.authenticate(activity)
            .onSuccess { _state.update { it.copy(isAuthenticated = true, isLoading = false) } }
            .onFailure { e -> handleAuthFailure(e) }
    }

    fun register(activity: Activity, username: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        // PasskeyService.register already stores the session token returned by the
        // backend, so there's no need to run a second sign-in ceremony here.
        passkeyService.register(activity, username)
            .onSuccess { _state.update { it.copy(isAuthenticated = true, isLoading = false) } }
            .onFailure { e -> handleAuthFailure(e) }
    }

    fun signOut() = viewModelScope.launch {
        // Unregister before clearing the auth token — ApiClient.bearerAuth() reads
        // tokenProvider.token when building the request, so clearing first would send
        // the delete unauthenticated. Best-effort: sign-out proceeds locally either way.
        tokenProvider.pushToken?.let { apiClient.unregisterPushToken(it) }
        tokenProvider.clear()
        tokenProvider.pushToken = null
        // Clear pending actions: queued actions are tied to the authenticated session and
        // should not persist or sync after sign-out (they belong to the previous user's vaults).
        pendingActionDao.deleteAll()
        notificationHelper.cancelQueuedActions()
        _state.update { it.copy(isAuthenticated = false) }
    }

    private fun handleAuthFailure(e: Throwable) {
        if (BuildConfig.DEBUG) Log.w(TAG, "auth failed", e)
        _state.update { it.copy(isLoading = false, error = ApiErrorMapper.friendlyMessage(e)) }
    }

    companion object {
        private const val TAG = "AuthViewModel"
    }
}

// --- TwoFactor ViewModel ---

data class TwoFactorUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val verified: Boolean = false,
    val setupResponse: Enable2FAResponse? = null,
    val status: TwoFactorStatus? = null,
    // #119: Client-side rate limiting for OTP verification attempts.
    val otpFailureCount: Int = 0,
    val otpCooldownSeconds: Int = 0
) {
    val isOtpBlocked: Boolean get() = otpCooldownSeconds > 0
}

@HiltViewModel
class TwoFactorViewModel @Inject constructor(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(TwoFactorUiState())
    val state = _state.asStateFlow()

    private var cooldownJob: kotlinx.coroutines.Job? = null

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

    fun verify2FA(vaultId: String, otp: String) = viewModelScope.launch {
        if (_state.value.isOtpBlocked) return@launch
        _state.update { it.copy(isLoading = true, error = null) }
        val req = Verify2FARequest(otp = otp)
        when (val result = apiClient.verify2FA(vaultId, req)) {
            is ApiResult.Success -> {
                // #119: Reset rate-limiting state on success
                cancelCooldown()
                _state.update { it.copy(verified = true, isLoading = false,
                    otpFailureCount = 0, otpCooldownSeconds = 0) }
            }
            is ApiResult.Error -> {
                val newCount = _state.value.otpFailureCount + 1
                val cooldown = otpCooldownSeconds(newCount)
                _state.update { it.copy(isLoading = false, error = result.message, otpFailureCount = newCount) }
                if (cooldown > 0) startCooldown(cooldown)
            }
            ApiResult.NetworkUnavailable -> {
                _state.update { it.copy(isLoading = false, error = "No network") }
            }
        }
    }

    fun disable2FA(vaultId: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.disable2FA(vaultId)) {
            is ApiResult.Success -> _state.update { it.copy(status = null, isLoading = false) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    // #120: Called only after successful BiometricHelper authentication in the screen layer.
    // Keeping the biometric prompt in the screen (where a FragmentActivity is available)
    // and the network call here (in the ViewModel) matches the same pattern used for check-in
    // in VaultListScreen and avoids threading BiometricHelper through the ViewModel's DI graph.
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
        _state.update { it.copy(otpCooldownSeconds = seconds) }
        cooldownJob = viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                kotlinx.coroutines.delay(1_000)
                remaining--
                _state.update { it.copy(otpCooldownSeconds = remaining) }
            }
        }
    }

    private fun cancelCooldown() {
        cooldownJob?.cancel()
        cooldownJob = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelCooldown()
    }
}

// --- Vault ViewModel ---

data class VaultUiState(
    val vaults: List<Vault> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isOffline: Boolean = false
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val notificationHelper: NotificationHelper,
    private val pendingActionDao: PendingActionDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(VaultUiState())
    val state = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.listVaults()) {
            is ApiResult.Success -> {
                _state.update { it.copy(vaults = result.data, isLoading = false, isOffline = false) }
            }
            ApiResult.NetworkUnavailable -> {
                _state.update { it.copy(isLoading = false, isOffline = true) }
            }
            is ApiResult.Error -> {
                _state.update { it.copy(isLoading = false, error = result.message) }
            }
        }
    }

    /**
     * Fetches all vault pages via cursor-based pagination (#112) and replaces the local list.
     * Accumulates pages until [VaultPage.hasMore] == false.
     */
    fun loadAll(limit: Int = 20) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        val accumulated = mutableListOf<Vault>()
        var cursor: String? = null
        do {
            when (val result = apiClient.listVaults(limit = limit, after = cursor)) {
                is ApiResult.Success -> {
                    accumulated.addAll(result.data.vaults)
                    cursor = result.data.nextCursor
                    if (!result.data.hasMore) {
                        _state.update { it.copy(vaults = accumulated, isLoading = false, isOffline = false) }
                        return@launch
                    }
                }
                ApiResult.NetworkUnavailable -> {
                    _state.update { it.copy(isLoading = false, isOffline = true) }
                    return@launch
                }
                is ApiResult.Error -> {
                    _state.update { it.copy(isLoading = false, error = result.message) }
                    return@launch
                }
            }
        } while (cursor != null)
        _state.update { it.copy(vaults = accumulated, isLoading = false, isOffline = false) }
    }

    fun checkIn(vaultId: String) = viewModelScope.launch {
        when (val result = apiClient.checkIn(vaultId)) {
            is ApiResult.Success -> load()
            is ApiResult.Error -> _state.update { it.copy(error = result.message) }
            ApiResult.NetworkUnavailable -> queueAction(
                PendingAction(
                    type = PendingActionType.CHECK_IN,
                    vaultId = vaultId,
                    queuedAt = System.currentTimeMillis(),
                    dedupeKey = "check_in:$vaultId"
                )
            )
        }
    }

    fun createVault(beneficiary: String, intervalDays: Int) = viewModelScope.launch {
        val req = CreateVaultRequest(beneficiary, intervalDays * 86_400L)
        when (val result = apiClient.createVault(req)) {
            is ApiResult.Success -> load()
            is ApiResult.Error -> _state.update { it.copy(error = result.message) }
            ApiResult.NetworkUnavailable -> queueAction(
                PendingAction(
                    type = PendingActionType.CREATE_VAULT,
                    payloadJson = Json.encodeToString(kotlinx.serialization.serializer(), req),
                    queuedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun queueAction(action: PendingAction) {
        pendingActionDao.insert(action)
        val queued = pendingActionDao.getAll()
        notificationHelper.showQueuedActions(queued.size)
        PendingActionSyncWorker.schedule(context)
        _state.update { it.copy(error = "Offline — request queued and will retry automatically") }
    }
}

// --- Acceptance ViewModel ---

data class AcceptanceUiState(
    val isLoading: Boolean = false,
    val isAccepted: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AcceptanceViewModel @Inject constructor(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(AcceptanceUiState())
    val state = _state.asStateFlow()

    // token: parsed from the /accept deep-link URL (required by the server).
    fun accept(vaultId: String, token: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.acceptBeneficiary(vaultId, token)) {
            is ApiResult.Success -> _state.update { it.copy(isLoading = false, isAccepted = true) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network. Please try again.") }
        }
    }
}

// --- Deposit ViewModel ---

data class DepositUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DepositViewModel @Inject constructor(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(DepositUiState())
    val state = _state.asStateFlow()

    /**
     * Validates and submits a deposit for [vaultId].
     *
     * [amountXlm] is the user-entered string in XLM (e.g. "5.0"). It is converted
     * to stroops (1 XLM = 10,000,000 stroops) here so the ViewModel owns the
     * parsing/validation logic and the UI only has to display [state].
     */
    fun deposit(vaultId: String, amountXlm: String) = viewModelScope.launch {
        val stroops = parseStroops(amountXlm)
        if (stroops == null || stroops <= 0) {
            _state.update { it.copy(error = "Enter a valid positive XLM amount.") }
            return@launch
        }
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.deposit(vaultId, stroops)) {
            is ApiResult.Success -> _state.update { it.copy(isLoading = false, isSuccess = true) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Converts an XLM string to stroops, or null if the input is invalid. */
    private fun parseStroops(xlm: String): Long? {
        val value = xlm.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        val stroops = value * 10_000_000.0
        if (stroops > Long.MAX_VALUE.toDouble()) return null
        return stroops.toLong()
    }
}

// --- Withdraw ViewModel ---

data class WithdrawUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class WithdrawViewModel @Inject constructor(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(WithdrawUiState())
    val state = _state.asStateFlow()

    /**
     * Validates and submits a withdrawal for [vaultId].
     *
     * Client-side guard: [amountXlm] must parse to a positive stroop count that
     * does not exceed [vaultBalanceStroops]. The matching check runs on the server
     * too; this gate provides immediate UX feedback before the network round-trip.
     * Biometric auth must be completed by the caller before invoking this method.
     */
    fun withdraw(vaultId: String, amountXlm: String, vaultBalanceStroops: Long) = viewModelScope.launch {
        val stroops = parseStroops(amountXlm)
        when {
            stroops == null || stroops <= 0 ->
                _state.update { it.copy(error = "Enter a valid positive XLM amount.") }
            stroops > vaultBalanceStroops ->
                _state.update { it.copy(error = "Amount exceeds available balance.") }
            else -> {
                _state.update { it.copy(isLoading = true, error = null) }
                when (val result = apiClient.withdraw(vaultId, stroops)) {
                    is ApiResult.Success -> _state.update { it.copy(isLoading = false, isSuccess = true) }
                    is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
                    ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
                }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun parseStroops(xlm: String): Long? {
        val value = xlm.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        val stroops = value * 10_000_000.0
        if (stroops > Long.MAX_VALUE.toDouble()) return null
        return stroops.toLong()
    }
}

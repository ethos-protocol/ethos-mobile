package com.ethosprotocol.ui

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethosprotocol.api.ApiClient
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
import com.ethosprotocol.services.CheckInSyncWorker
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.services.PendingCheckIn
import com.ethosprotocol.services.PendingCheckInDao
import com.ethosprotocol.widget.VaultStatusWidget
import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// --- Auth ViewModel ---

data class AuthUiState(
    val isAuthenticated: Boolean = false,
    val isLocked: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val passkeyService: PasskeyService,
    private val tokenProvider: TokenProvider,
    private val pendingCheckInDao: PendingCheckInDao,
    private val notificationHelper: NotificationHelper,
    private val workManager: WorkManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState(isAuthenticated = tokenProvider.token != null))
    val state = _state.asStateFlow()

    private var backgroundedAtMillis: Long? = null

    // Default re-lock timeout — kept in sync with iOS's equivalent (#12) so both
    // platforms re-prompt biometrics after the same amount of backgrounded time.
    var relockTimeoutMillis: Long = DEFAULT_RELOCK_TIMEOUT_MILLIS

    /** Called from MainActivity.onStop — records when the app left the foreground. */
    fun onAppBackgrounded(now: Long = System.currentTimeMillis()) {
        if (_state.value.isAuthenticated) backgroundedAtMillis = now
    }

    /** Called from MainActivity.onStart — locks the session if it was backgrounded past the timeout. */
    fun onAppForegrounded(now: Long = System.currentTimeMillis()) {
        val backgroundedAt = backgroundedAtMillis ?: return
        backgroundedAtMillis = null
        if (_state.value.isAuthenticated && now - backgroundedAt >= relockTimeoutMillis) {
            _state.update { it.copy(isLocked = true) }
        }
    }

    fun unlock() {
        _state.update { it.copy(isLocked = false) }
    }

    fun signIn(activity: Activity) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        passkeyService.authenticate(activity)
            .onSuccess { _state.update { it.copy(isAuthenticated = true, isLocked = false, isLoading = false) } }
            .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
    }

    fun register(activity: Activity, username: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        passkeyService.register(activity, username)
            .onSuccess { signIn(activity) }
            .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
    }

    // On a shared or handed-down device, anything left behind here (queued check-ins,
    // scheduled workers, the widget's cached vault data, the "check-in queued"
    // notification) can surface the previous user's vault data to whoever signs in next.
    fun signOut() = viewModelScope.launch {
        tokenProvider.clear()
        backgroundedAtMillis = null

        pendingCheckInDao.deleteAll()
        workManager.cancelUniqueWork(CheckInSyncWorker.WORK_NAME)
        workManager.cancelUniqueWork(VaultWidgetUpdateWorker.WORK_NAME)

        VaultStatusWidget.clearVaultData(context)
        VaultStatusWidget.refreshAll(context)

        notificationHelper.cancelQueuedCheckIn()

        _state.update { it.copy(isAuthenticated = false, isLocked = false) }
    }

    companion object {
        const val DEFAULT_RELOCK_TIMEOUT_MILLIS = 30_000L
    }
}

// --- TwoFactor ViewModel ---

data class TwoFactorUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val verified: Boolean = false,
    val setupResponse: Enable2FAResponse? = null,
    val status: TwoFactorStatus? = null
)

@HiltViewModel
class TwoFactorViewModel @Inject constructor(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(TwoFactorUiState())
    val state = _state.asStateFlow()

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
        _state.update { it.copy(isLoading = true, error = null) }
        val req = Verify2FARequest(otp = otp)
        when (val result = apiClient.verify2FA(vaultId, req)) {
            is ApiResult.Success -> _state.update { it.copy(verified = true, isLoading = false) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
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
    private val pendingCheckInDao: PendingCheckInDao,
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

    fun checkIn(vaultId: String) = viewModelScope.launch {
        when (val result = apiClient.checkIn(vaultId)) {
            is ApiResult.Success -> load()
            is ApiResult.Error -> _state.update { it.copy(error = result.message) }
            ApiResult.NetworkUnavailable -> {
                pendingCheckInDao.insert(PendingCheckIn(vaultId = vaultId, queuedAt = System.currentTimeMillis()))
                val queued = pendingCheckInDao.getAll()
                notificationHelper.showQueuedCheckIn(queued.size)
                CheckInSyncWorker.schedule(context)
                _state.update { it.copy(error = "Offline — check-in queued and will retry automatically") }
            }
        }
    }

    fun createVault(beneficiary: String, intervalDays: Int) = viewModelScope.launch {
        val req = CreateVaultRequest(beneficiary, intervalDays * 86_400L)
        when (val result = apiClient.createVault(req)) {
            is ApiResult.Success -> load()
            is ApiResult.Error -> _state.update { it.copy(error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(error = "No network") }
        }
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

    fun accept(vaultId: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.acceptBeneficiary(vaultId)) {
            is ApiResult.Success -> _state.update { it.copy(isLoading = false, isAccepted = true) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network. Please try again.") }
        }
    }
}

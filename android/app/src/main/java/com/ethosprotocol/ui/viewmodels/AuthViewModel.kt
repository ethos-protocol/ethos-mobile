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
// --- Auth ViewModel ---

@Immutable
data class AuthUiState(
    val isAuthenticated: Boolean = false,
    val isLocked: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    // Set once initiateRecovery() succeeds — its presence means a recovery code has been
    // sent and the UI should show the "finish recovery" step.
    val recoveryToken: String? = null,
    val cooldownRemainingSeconds: Int = 0
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val passkeyService: PasskeyService,
    private val tokenProvider: TokenProvider,
    private val notificationHelper: NotificationHelper,
    private val pendingActionDao: PendingActionDao,
    // #200: cross-device sync of non-secret vault-to-passkey-credential associations.
    private val vaultAssociationStore: VaultAssociationStore
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState(isAuthenticated = tokenProvider.token != null))
    val state = _state.asStateFlow()

    private var backgroundedAtMillis: Long? = null

    // Default re-lock timeout — kept in sync with iOS's equivalent (#12) so both
    // platforms re-prompt biometrics after the same amount of backgrounded time.
    var relockTimeoutMillis: Long = DEFAULT_RELOCK_TIMEOUT_MILLIS

    private var consecutiveFailures = 0
    private var cooldownJob: Job? = null

    // #209: proactive token refresh, scheduled independently of request activity — ApiClient's
    // ensureFreshToken() already covers "refresh before the next API call", but a foregrounded,
    // idle app makes no calls and would otherwise sit on a token until it's rejected. Polling
    // (rather than a one-shot timer at the exact expiry, as AuthStore.swift does) is used because
    // TokenProvider only exposes isNearExpiry(), not the raw expiry instant.
    private var refreshJob: Job? = null

    init {
        if (tokenProvider.token != null) startScheduledRefresh()
    }

    private fun startScheduledRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                delay(REFRESH_POLL_INTERVAL_MILLIS)
                if (tokenProvider.token == null) return@launch
                if (!tokenProvider.isNearExpiry()) continue
                when (val result = apiClient.refreshToken()) {
                    is ApiResult.Success -> tokenProvider.setSession(result.data)
                    is ApiResult.Error -> if (result.code == 401) {
                        // ApiClient already cleared the stored token on the 401; reflect that
                        // here so the UI falls back to full re-authentication.
                        _state.update { it.copy(isAuthenticated = false) }
                        return@launch
                    }
                    ApiResult.NetworkUnavailable -> Unit // transient — retry on the next tick
                }
            }
        }
    }

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
        if (_state.value.cooldownRemainingSeconds > 0) return@launch
        _state.update { it.copy(isLoading = true, error = null) }
        passkeyService.authenticate(activity)
            .onSuccess {
                consecutiveFailures = 0
                cooldownJob?.cancel()
                _state.update { it.copy(isAuthenticated = true, isLoading = false, cooldownRemainingSeconds = 0) }
                startScheduledRefresh()
            }
            .onFailure { e -> handleAuthFailure(e) }
    }

    private fun startCooldownIfNeeded() {
        if (consecutiveFailures < COOLDOWN_FAILURE_THRESHOLD) return
        // Cap the exponent well below where `shl` would overflow Int — the clamp to
        // COOLDOWN_MAX_SECONDS below makes any exponent past this point equivalent anyway.
        val exponent = (consecutiveFailures - COOLDOWN_FAILURE_THRESHOLD).coerceAtMost(10)
        val seconds = (COOLDOWN_BASE_SECONDS shl exponent).coerceAtMost(COOLDOWN_MAX_SECONDS)
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _state.update { it.copy(cooldownRemainingSeconds = remaining) }
                delay(1_000)
            }
            _state.update { it.copy(cooldownRemainingSeconds = 0) }
        }
    }

    fun register(activity: Activity, username: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        // PasskeyService.register already stores the session token returned by the
        // backend, so there's no need to run a second sign-in ceremony here.
        passkeyService.register(activity, username)
            .onSuccess {
                _state.update { it.copy(isAuthenticated = true, isLoading = false) }
                startScheduledRefresh()
            }
            .onFailure { e -> handleAuthFailure(e) }
    }

    // ── Account recovery ("lost your device?") — mirrors iOS AuthStore.recoverAccess (#211) ──

    fun sendRecoveryCode(username: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.initiateRecovery(RecoveryInitiateRequest(username))) {
            is ApiResult.Success -> _state.update { it.copy(isLoading = false, recoveryToken = result.data.recoveryToken) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun finishRecovery(activity: Activity, username: String) = viewModelScope.launch {
        val recoveryToken = _state.value.recoveryToken ?: return@launch
        _state.update { it.copy(isLoading = true, error = null) }
        passkeyService.recoverAccount(activity, username, recoveryToken)
            .onSuccess {
                _state.update { it.copy(isAuthenticated = true, isLoading = false, recoveryToken = null) }
                startScheduledRefresh()
            }
            .onFailure { e ->
                // A rejected recovery token/proof (401) can't be retried as-is — drop back to
                // the "send code" step instead of leaving the user stuck on a dead-end error.
                val recoveryRejected = (e as? ApiCallFailedException)?.code == 401
                val message = if (e is PasskeyException) e.message ?: "Recovery failed. Please try again."
                              else ApiErrorMapper.friendlyMessage(e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = message,
                        recoveryToken = if (recoveryRejected) null else it.recoveryToken
                    )
                }
            }
    }

    fun clearRecovery() {
        _state.update { it.copy(recoveryToken = null, error = null) }
    }

    fun signOut() = viewModelScope.launch {
        refreshJob?.cancel()
        refreshJob = null
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
        // #200: local vault-to-credential associations belong to the previous session;
        // the synced copy is left alone so another signed-in device isn't affected.
        vaultAssociationStore.clearAll()
        backgroundedAtMillis = null
        consecutiveFailures = 0
        cooldownJob?.cancel()
        _state.update { it.copy(isAuthenticated = false, isLocked = false, cooldownRemainingSeconds = 0) }
    }

    private fun handleAuthFailure(e: Throwable) {
        if (BuildConfig.DEBUG) Log.w(TAG, "auth failed", e)
        consecutiveFailures++
        _state.update { it.copy(isLoading = false, error = ApiErrorMapper.friendlyMessage(e)) }
        startCooldownIfNeeded()
    }

    companion object {
        private const val TAG = "AuthViewModel"
        const val DEFAULT_RELOCK_TIMEOUT_MILLIS = 30_000L
        private const val COOLDOWN_FAILURE_THRESHOLD = 3
        private const val COOLDOWN_BASE_SECONDS = 2
        private const val COOLDOWN_MAX_SECONDS = 60
        // #209: matches TokenProvider.isNearExpiry's default 60s threshold — checking every
        // 30s guarantees at least one check lands inside that window before expiry.
        private const val REFRESH_POLL_INTERVAL_MILLIS = 30_000L
    }
}


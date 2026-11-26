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
// --- Withdraw ViewModel ---

@Immutable
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

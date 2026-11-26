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
// --- Passkey Management ViewModel (#206) ---

data class PasskeyManagementUiState(
    val credentials: List<PasskeyCredential> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class PasskeyManagementViewModel @Inject constructor(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(PasskeyManagementUiState())
    val state = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.listCredentials()) {
            is ApiResult.Success -> _state.update { it.copy(credentials = result.data, isLoading = false) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    // Called only after successful BiometricHelper authentication in the screen layer (#206),
    // mirroring TwoFactorViewModel.disable2FAAfterBiometric — revoking a passkey is at least
    // as security-sensitive as disabling 2FA.
    fun revokeCredentialAfterBiometric(credentialId: String) = viewModelScope.launch {
        when (val result = apiClient.revokeCredential(credentialId)) {
            is ApiResult.Success ->
                _state.update { it.copy(credentials = it.credentials.filterNot { c -> c.credentialId == credentialId }) }
            is ApiResult.Error -> _state.update { it.copy(error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(error = "No network") }
        }
    }
}


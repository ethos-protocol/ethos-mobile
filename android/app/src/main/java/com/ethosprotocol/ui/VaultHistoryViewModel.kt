package com.ethosprotocol.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.VaultHistoryEvent
import com.ethosprotocol.models.VaultHistoryPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class VaultHistoryUiState(
    val events: List<VaultHistoryEvent> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** Loads a vault's activity history and appends further pages via cursor pagination (#217). */
@HiltViewModel
class VaultHistoryViewModel @Inject constructor(
    private val apiClient: ApiClient,
) : ViewModel() {

    private val _state = MutableStateFlow(VaultHistoryUiState())
    val state: StateFlow<VaultHistoryUiState> = _state.asStateFlow()

    /** Replaces the list with the first page of history for [vaultId]. */
    fun load(vaultId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            applyResult(apiClient.getVaultHistory(vaultId), replace = true)
        }
    }

    /** Appends the next page. No-op when the server reported no further pages. */
    fun loadMore(vaultId: String) {
        val current = _state.value
        val cursor = current.nextCursor
        if (!current.hasMore || cursor == null || current.isLoading) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            applyResult(apiClient.getVaultHistory(vaultId, after = cursor), replace = false)
        }
    }

    private fun applyResult(result: ApiResult<VaultHistoryPage>, replace: Boolean) {
        _state.update { current ->
            when (result) {
                is ApiResult.Success -> current.copy(
                    events = if (replace) result.data.events else current.events + result.data.events,
                    nextCursor = result.data.nextCursor,
                    hasMore = result.data.hasMore,
                    isLoading = false,
                    error = null,
                )
                is ApiResult.NetworkUnavailable -> current.copy(isLoading = false, error = "No network")
                is ApiResult.Error -> current.copy(isLoading = false, error = result.message)
                else -> current.copy(isLoading = false, error = "Unknown error")
            }
        }
    }
}

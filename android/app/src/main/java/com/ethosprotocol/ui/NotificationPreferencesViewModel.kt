package com.ethosprotocol.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.NotificationPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationPreferencesUiState(
    val preferences: NotificationPreferences = NotificationPreferences(),
    val isSaving: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class NotificationPreferencesViewModel @Inject constructor(
    private val apiClient: ApiClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(
        NotificationPreferencesUiState(
            preferences = NotificationPreferences.load(context)
        )
    )
    val state = _state.asStateFlow()

    /**
     * Persists updated preferences locally and syncs them server-side so they
     * survive reinstall — mirrors iOS NotificationPreferencesView.save().
     */
    fun update(preferences: NotificationPreferences) {
        NotificationPreferences.save(context, preferences)
        _state.update { it.copy(preferences = preferences, error = null) }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            when (val result = apiClient.updateNotificationPreferences(preferences)) {
                is ApiResult.Success -> _state.update { it.copy(isSaving = false) }
                is ApiResult.Error -> _state.update { it.copy(isSaving = false, error = result.message) }
                ApiResult.NetworkUnavailable -> _state.update {
                    // Offline: local save already happened; server-side sync will be retried
                    // on the next manual save. Don't surface an error for a best-effort call.
                    it.copy(isSaving = false)
                }
            }
        }
    }
}

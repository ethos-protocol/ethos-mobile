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
// --- Vault ViewModel ---

@Immutable
data class VaultUiState(
    val vaults: List<Vault> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val isOffline: Boolean = false,
    val beneficiaryUpdated: Boolean = false,
    // True for the duration of an in-flight createVault() call, so the UI can disable the
    // submit control and a second tap can't reach the network/queue while the first is
    // still pending — without this, two rapid taps while offline queue two structurally
    // identical CREATE_VAULT actions (CREATE_VAULT has no natural dedupeKey) and both get
    // submitted once connectivity returns, creating two real vaults for one user action.
    val isCreatingVault: Boolean = false
)

private const val PAGE_SIZE = 20

// #486: Upper bound on how many vaults loadAll() will hold in memory at once. A user
// with an unusually large number of vaults shouldn't balloon the app's heap just to
// populate the list — this cap is generous enough to only trim extreme cases.
private const val MAX_CACHED_VAULTS = 500
private const val VAULT_VM_TAG = "VaultViewModel"

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val notificationHelper: NotificationHelper,
    private val pendingActionDao: PendingActionDao,
    private val vaultEventSocket: VaultEventSocket,
    private val expiringVaultsManager: com.ethosprotocol.services.ExpiringVaultsManager,
    private val offlineCache: OfflineCache,
    private val networkMonitor: NetworkMonitor,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(VaultUiState())
    val state = _state.asStateFlow()

    val expiringVaultsState = expiringVaultsManager.bannerState

    private var nextCursor: String? = null
    private val eventJobs = mutableMapOf<String, Job>()

    // #606: Highest Vault.version applied per vault id. A GET /vaults/{id} started before a
    // `vault_updated` push but completed after it must not overwrite the newer pushed state,
    // so every merge path (poll, push, list reload) drops any Vault whose version is not
    // strictly greater than the one already applied.
    private val appliedVersions = mutableMapOf<String, Long>()

    init {
        // #428: Observe network reachability. When connectivity is restored after an
        // offline period, immediately:
        //   1. Reload vaults so the list reflects current server state.
        //   2. Trigger PendingActionSyncWorker to drain any queued offline actions.
        // The distinctUntilChanged() in connectivityFlow prevents spurious reloads on
        // repeated true emissions (e.g. capability changes on the same network).
        viewModelScope.launch {
            networkMonitor.connectivityFlow.collect { isOnline ->
                val wasOffline = _state.value.isOffline
                _state.update { it.copy(isOffline = !isOnline) }
                if (isOnline && wasOffline) {
                    // Back online — reload and drain the pending-action queue.
                    load()
                    com.ethosprotocol.services.PendingActionSyncWorker.schedule(context)
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        // Invalidate cache on manual refresh to ensure fresh data
        offlineCache.invalidate("/vaults")
        when (val result = apiClient.listVaults(limit = PAGE_SIZE)) {
            is ApiResult.Success -> {
                nextCursor = result.data.nextCursor
                // #606: A list response can also be stale relative to a push received while
                // it was in flight — keep the newer local copy for any vault whose applied
                // version is ahead of the one in this response.
                val merged = result.data.vaults.map { incoming ->
                    val applied = appliedVersions[incoming.id]
                    if (applied != null && incoming.version <= applied) {
                        _state.value.vaults.firstOrNull { it.id == incoming.id } ?: incoming
                    } else {
                        appliedVersions[incoming.id] = incoming.version
                        incoming
                    }
                }
                _state.update {
                    it.copy(
                        vaults = merged,
                        isLoading = false,
                        isOffline = false,
                        hasMore = result.data.hasMore
                    )
                }
                expiringVaultsManager.updateVaults(merged)
                subscribeToEvents(merged.map { it.id })
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
     * Fetches the next page (via [nextCursor], captured by the preceding [load]) and appends it
     * to the current list, for the "Load more" control in VaultListScreen's LazyColumn.
     */
    fun loadMore() {
        val cursor = nextCursor ?: return
        if (_state.value.isLoadingMore) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = apiClient.listVaults(limit = PAGE_SIZE, after = cursor)) {
                is ApiResult.Success -> {
                    nextCursor = result.data.nextCursor
                    val allVaults = _state.value.vaults + result.data.vaults
                    _state.update {
                        it.copy(
                            vaults = allVaults,
                            isLoadingMore = false,
                            hasMore = result.data.hasMore
                        )
                    }
                    expiringVaultsManager.updateVaults(allVaults)
                    subscribeToEvents(result.data.vaults.map { it.id })
                }
                ApiResult.NetworkUnavailable -> {
                    _state.update { it.copy(isLoadingMore = false, isOffline = true) }
                }
                is ApiResult.Error -> {
                    _state.update { it.copy(isLoadingMore = false, error = result.message) }
                }
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
                    // #486: Trim from the front once the cap is exceeded instead of letting
                    // every subsequent page grow the list unbounded.
                    if (accumulated.size > MAX_CACHED_VAULTS) {
                        Log.w(VAULT_VM_TAG, "loadAll: ${accumulated.size} vaults exceeds cap " +
                            "$MAX_CACHED_VAULTS — trimming oldest entries")
                        while (accumulated.size > MAX_CACHED_VAULTS) accumulated.removeAt(0)
                    }
                    cursor = result.data.nextCursor
                    if (!result.data.hasMore) {
                        _state.update { it.copy(vaults = accumulated, isLoading = false, isOffline = false) }
                        expiringVaultsManager.updateVaults(accumulated)
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
        expiringVaultsManager.updateVaults(accumulated)
    }

    fun dismissExpiringVaultsBanner() {
        expiringVaultsManager.dismissBanner()
    }

    // Keeps one VaultEventSocket subscription per vault currently in [_state], so
    // check-ins/deposits/withdrawals made elsewhere (another device, an expiry)
    // update this list in place instead of requiring a manual refresh.
    private fun subscribeToEvents(vaultIds: List<String>) {
        val currentIds = vaultIds.toSet()
        eventJobs.keys.filter { it !in currentIds }.forEach { id -> eventJobs.remove(id)?.cancel() }
        currentIds.filterNot { eventJobs.containsKey(it) }.forEach { id ->
            eventJobs[id] = viewModelScope.launch {
                vaultEventSocket.events(id).collect { event ->
                    val updated = event.vault ?: return@collect
                    // Same merge path as a poll response (updateVaultInPlace) — see the
                    // "Reconciling a poll/push disagreement" rule in api-contract.md (#223):
                    // whichever one is received last simply overwrites in place.
                    updateVaultInPlace(updated)
                }
            }
        }
    }

    override fun onCleared() {
        eventJobs.values.forEach { it.cancel() }
        eventJobs.clear()
        appliedVersions.clear()
    }

    fun checkIn(vaultId: String) = viewModelScope.launch {
        // Generated once and reused for both this attempt and any subsequent queued retry, so
        // a resubmission after a process death is identifiable to the server as a duplicate of
        // this specific attempt rather than a brand-new check-in.
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        when (val result = apiClient.checkIn(vaultId, idempotencyKey)) {
            is ApiResult.Success -> refreshSingle(vaultId)
            is ApiResult.Error -> _state.update { it.copy(error = result.message) }
            ApiResult.NetworkUnavailable -> queueAction(
                PendingAction(
                    type = PendingActionType.CHECK_IN,
                    vaultId = vaultId,
                    queuedAt = System.currentTimeMillis(),
                    dedupeKey = "check_in:$vaultId",
                    idempotencyKey = idempotencyKey
                )
            )
        }
    }

    /** Refetches a single vault and patches it into the existing list, instead of reloading all vaults. */
    fun refreshSingle(vaultId: String) = viewModelScope.launch {
        when (val result = apiClient.getVault(vaultId)) {
            is ApiResult.Success -> updateVaultInPlace(result.data)
            is ApiResult.Error -> _state.update { it.copy(error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(error = "No network") }
        }
    }

    // Shared merge point for both a poll response (refreshSingle) and a `vault_updated`
    // push (subscribeToEvents). #606: last-applied-wins is not enough — a GET started
    // before a push but completed after it would clobber the newer state, so an incoming
    // Vault is only applied when its version is strictly greater than the one already
    // applied for that vault. Equal versions are ignored to avoid needless recomposition.
    private fun updateVaultInPlace(vault: Vault) {
        val applied = appliedVersions[vault.id]
        if (applied != null && vault.version <= applied) return
        appliedVersions[vault.id] = vault.version
        _state.update { state ->
            val updated = state.vaults.map { if (it.id == vault.id) vault else it }
            if (updated === state.vaults) state else state.copy(vaults = updated)
        }
    }

    /// Update the beneficiary for a vault (owner-only). On success the vault is
    /// refreshed in place so the UI reflects the new beneficiary immediately — matching the
    /// same pattern used by checkIn(). Mirrors iOS VaultStore.updateBeneficiary.
    /// Uses refreshSingle() instead of load() to avoid redundant full-list fetches (#320).
    fun updateBeneficiary(vaultId: String, newBeneficiary: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null, beneficiaryUpdated = false) }
        when (val result = apiClient.updateBeneficiary(vaultId, newBeneficiary)) {
            is ApiResult.Success -> {
                _state.update { it.copy(isLoading = false, beneficiaryUpdated = true) }
                refreshSingle(vaultId)
            }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun clearBeneficiaryUpdated() {
        _state.update { it.copy(beneficiaryUpdated = false) }
    }

    fun createVault(beneficiary: String, intervalDays: Int) = viewModelScope.launch {
        // Guards against a second tap reaching the network/queue while the first createVault()
        // call is still in flight (see VaultUiState.isCreatingVault).
        if (_state.value.isCreatingVault) return@launch
        _state.update { it.copy(isCreatingVault = true) }

        val req = CreateVaultRequest(beneficiary, intervalDays * 86_400L)
        // Reused for any queued retry, same rationale as checkIn()'s idempotencyKey.
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        when (val result = apiClient.createVault(req, idempotencyKey)) {
            is ApiResult.Success -> {
                _state.update { it.copy(isCreatingVault = false) }
                load()
            }
            is ApiResult.Error -> _state.update { it.copy(isCreatingVault = false, error = result.message) }
            ApiResult.NetworkUnavailable -> {
                queueAction(
                    PendingAction(
                        type = PendingActionType.CREATE_VAULT,
                        payloadJson = Json.encodeToString(kotlinx.serialization.serializer(), req),
                        queuedAt = System.currentTimeMillis(),
                        idempotencyKey = idempotencyKey
                    )
                )
                _state.update { it.copy(isCreatingVault = false) }
            }
        }
    }

    private suspend fun queueAction(action: PendingAction) {
        pendingActionDao.insert(action)
        // #483: count() instead of getAll().size — avoids pulling every queued
        // row's full columns across the binder just to show a badge count.
        val queuedCount = pendingActionDao.count()
        notificationHelper.showQueuedActions(queuedCount)
        PendingActionSyncWorker.schedule(context)
        _state.update { it.copy(error = "Offline — request queued and will retry automatically") }
    }
}


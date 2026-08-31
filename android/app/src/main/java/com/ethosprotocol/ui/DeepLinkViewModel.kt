package com.ethosprotocol.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.ethosprotocol.services.VaultDeepLink
import com.ethosprotocol.services.VaultDeepLinkAction
import com.ethosprotocol.services.DeepLinkSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Holds pending navigation targets that arrive via deep link or beneficiary-accept intent.
 *
 * Both fields are backed by [SavedStateHandle] so they survive:
 *   - configuration changes (screen rotation, locale switch, etc.)
 *   - process death / recreation (system low-memory kill)
 *
 * Previously these were plain `mutableStateOf` fields on `MainActivity`, which meant any
 * deep-link tap during authentication would be silently lost the moment the system recycled
 * the process before the user finished signing in.  (Issue #93)
 *
 * Persistence strategy
 * --------------------
 * `SavedStateHandle` serialises values via Android's `Bundle` mechanism using
 * `Parcelable` or a small set of primitive-compatible types.  Rather than adding a
 * `@Parcelize` annotation to the existing `VaultDeepLink` data class (which would add an
 * Android-framework dependency to a pure-data type), we decompose the value into two
 * `String?` keys and reconstruct it on the way out.  This keeps the model layer clean.
 */
@HiltViewModel
class DeepLinkViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // -------------------------------------------------------------------------
    // Keys stored in SavedStateHandle
    // -------------------------------------------------------------------------

    companion object {
        internal const val KEY_BENEFICIARY_VAULT_ID = "pending_beneficiary_vault_id"
        internal const val KEY_BENEFICIARY_TOKEN    = "pending_beneficiary_token"
        internal const val KEY_DEEP_LINK_VAULT_ID   = "pending_deep_link_vault_id"
        internal const val KEY_DEEP_LINK_ACTION     = "pending_deep_link_action"
        internal const val KEY_DEEP_LINK_SOURCE     = "pending_deep_link_source"
    }

    // -------------------------------------------------------------------------
    // Exposed state as StateFlow (collected by Compose via collectAsStateWithLifecycle)
    // -------------------------------------------------------------------------

    /** (vaultId, token) from an /accept beneficiary intent (#109), or null when none is pending. */
    val pendingBeneficiaryAccept: StateFlow<Pair<String, String>?> =
        // SavedStateHandle does not know how to serialise Pair directly, so we store the two
        // string components separately and derive the composite value here — same approach
        // as pendingVaultDeepLink below.
        object : StateFlow<Pair<String, String>?> {
            private val delegate = run {
                val vaultIdFlow: StateFlow<String?> =
                    savedStateHandle.getStateFlow(KEY_BENEFICIARY_VAULT_ID, null)
                val tokenFlow: StateFlow<String?> =
                    savedStateHandle.getStateFlow(KEY_BENEFICIARY_TOKEN, null)
                object : StateFlow<Pair<String, String>?> {
                    override val replayCache get() = listOf(value)
                    override val value: Pair<String, String>?
                        get() {
                            val id = vaultIdFlow.value ?: return null
                            val token = tokenFlow.value ?: return null
                            return id to token
                        }

                    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Pair<String, String>?>) =
                        vaultIdFlow.collect { collector.emit(value) }
                }
            }
            override val replayCache get() = delegate.replayCache
            override val value get() = delegate.value
            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Pair<String, String>?>) =
                delegate.collect(collector)
        }

    /** Deep link parsed from an ethosprotocol:// URI, or null when none is pending. */
    val pendingVaultDeepLink: StateFlow<VaultDeepLink?> =
        // SavedStateHandle does not know how to serialise VaultDeepLink directly, so we
        // store the components separately and derive the composite value here.
        object : StateFlow<VaultDeepLink?> {
            // Delegate to a derived flow that combines the components.
            private val delegate = run {
                val vaultIdFlow: StateFlow<String?> =
                    savedStateHandle.getStateFlow(KEY_DEEP_LINK_VAULT_ID, null)
                val actionFlow: StateFlow<String?> =
                    savedStateHandle.getStateFlow(KEY_DEEP_LINK_ACTION, null)
                val sourceFlow: StateFlow<String?> =
                    savedStateHandle.getStateFlow(KEY_DEEP_LINK_SOURCE, null)
                // Derive a simple StateFlow by implementing it ourselves.  A coroutine-based
                // combine() would require a scope, so we implement a lightweight wrapper that
                // reads the three underlying flows on every access — acceptable here because
                // these values change rarely (only on new intent / process start).
                object : StateFlow<VaultDeepLink?> {
                    override val replayCache get() = listOf(value)
                    override val value: VaultDeepLink?
                        get() {
                            val id = vaultIdFlow.value ?: return null
                            val action = actionFlow.value?.let {
                                VaultDeepLinkAction.fromPathSegment(it)
                            } ?: return null
                            val source = sourceFlow.value?.let {
                                DeepLinkSource.fromString(it)
                            } ?: DeepLinkSource.UNKNOWN
                            return VaultDeepLink(vaultId = id, action = action, source = source)
                        }

                    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<VaultDeepLink?>) =
                        vaultIdFlow.collect { collector.emit(value) }
                }
            }
            override val replayCache get() = delegate.replayCache
            override val value get() = delegate.value
            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<VaultDeepLink?>) =
                delegate.collect(collector)
        }

    // -------------------------------------------------------------------------
    // Mutators
    // -------------------------------------------------------------------------

    /** Called from [MainActivity.handleIncomingIntent] when a beneficiary-accept URI arrives. */
    fun setPendingBeneficiaryAccept(accept: Pair<String, String>?) {
        savedStateHandle[KEY_BENEFICIARY_VAULT_ID] = accept?.first
        savedStateHandle[KEY_BENEFICIARY_TOKEN] = accept?.second
    }

    /** Called from [MainActivity.handleIncomingIntent] when an ethosprotocol:// URI arrives. */
    fun setPendingVaultDeepLink(deepLink: VaultDeepLink?) {
        savedStateHandle[KEY_DEEP_LINK_VAULT_ID] = deepLink?.vaultId
        savedStateHandle[KEY_DEEP_LINK_ACTION]   = deepLink?.action?.pathSegment
        savedStateHandle[KEY_DEEP_LINK_SOURCE]   = deepLink?.source?.value
    }

    /** Clears the beneficiary-accept state once the navigation target has been consumed. */
    fun consumeBeneficiaryAccept() = setPendingBeneficiaryAccept(null)

    /** Clears the vault deep-link state once the navigation target has been consumed. */
    fun consumeVaultDeepLink() = setPendingVaultDeepLink(null)
}

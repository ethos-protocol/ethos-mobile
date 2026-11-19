package com.ethosprotocol.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// One of possibly several passkeys registered to the authenticated account (#206, #207) —
// an account is not limited to a single credential, so this is always modeled as a list
// (`List<PasskeyCredential>`), never a lone value.
@Serializable
data class PasskeyCredential(
    @SerialName("credential_id") val credentialId: String,
    @SerialName("device_label") val deviceLabel: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null
)

/**
 * Network quality tiers used to adapt image quality and API pagination.
 * Ordered from best (WIFI) to worst (SLOW_2G).
 */
enum class NetworkQualityTier {
    WIFI,
    CELLULAR_5G,
    CELLULAR_4G,
    CELLULAR_2G;

    /** Relative image quality (0..100) to request for this tier. */
    val imageQuality: Int
        get() = when (this) {
            WIFI -> 100
            CELLULAR_5G -> 85
            CELLULAR_4G -> 65
            CELLULAR_2G -> 40
        }

    /** Page size to use for paginated API requests on this tier. */
    val pageSize: Int
        get() = when (this) {
            WIFI -> 50
            CELLULAR_5G -> 40
            CELLULAR_4G -> 25
            CELLULAR_2G -> 10
        }

    val isCellular: Boolean
        get() = this != WIFI
}

/**
 * User-facing quality preference. AUTO defers to detected network conditions;
 * HIGH / LOW override automatic detection.
 */
enum class QualityPreference {
    AUTO,
    HIGH,
    LOW;

    /**
     * Resolve the effective tier given the detected network tier.
     * HIGH forces WiFi-grade quality, LOW forces the most conservative tier.
     */
    fun resolve(detected: NetworkQualityTier): NetworkQualityTier = when (this) {
        AUTO -> detected
        HIGH -> NetworkQualityTier.WIFI
        LOW -> NetworkQualityTier.CELLULAR_2G
    }
}

/**
 * Detects the current network type and maps it to a [NetworkQualityTier].
 */
object NetworkQualityDetector {

    fun detect(context: Context): NetworkQualityTier {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkQualityTier.CELLULAR_4G
        val network = cm.activeNetwork ?: return NetworkQualityTier.CELLULAR_2G
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkQualityTier.CELLULAR_2G

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            return NetworkQualityTier.WIFI
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ->
                    NetworkQualityTier.CELLULAR_5G
                caps.linkDownstreamBandwidthKbps >= 10_000 -> NetworkQualityTier.CELLULAR_5G
                caps.linkDownstreamBandwidthKbps >= 2_000 -> NetworkQualityTier.CELLULAR_4G
                else -> NetworkQualityTier.CELLULAR_2G
            }
        }

        return NetworkQualityTier.CELLULAR_4G
    }

    /**
     * Resolve the effective tier honoring the user's [QualityPreference].
     */
    fun resolve(context: Context, preference: QualityPreference): NetworkQualityTier =
        preference.resolve(detect(context))
}

/**
 * A vault as returned by the server.
 *
 * [version] is a server-assigned, monotonically increasing revision used to
 * reconcile out-of-order updates (see shared/api-contract.md §Reconciling, #223
 * and #606). When comparing two snapshots of the same vault, the one with the
 * higher [version] is newer and must win; a snapshot with a lower or equal
 * version must be discarded rather than applied.
 *
 * Older servers that do not yet emit a version field are treated as
 * [DEFAULT_VERSION], which is lower than any version a versioned server will
 * assign. This keeps backward compatibility: an unversioned snapshot will never
 * overwrite a versioned one, and among unversioned snapshots the last-applied
 * value is retained.
 */
data class Vault(
    val id: String,
    val name: String = id,
    val version: Long = DEFAULT_VERSION,
    // Fields below were introduced with the vault list/detail UI (#216-#219). They default
    // so that responses and constructors that only supply id/name/version keep working.
    val owner: String = "",
    val beneficiary: String = "",
    val balance: Long = 0L,
    val checkInInterval: Long = 0L,
    val lastCheckIn: String = "",
    val ttlRemaining: Long? = null,
    val status: VaultStatus = VaultStatus.active,
    // Optional owner-set display name (#218). Null until set.
    val label: String? = null
) {
    /** True when the vault's time-to-live is under one day (#219 "Expiring Soon" filter). */
    val isExpiringSoon: Boolean get() = (ttlRemaining ?: Long.MAX_VALUE) < 86_400L

    companion object {
        /**
         * Version assigned to vaults from servers that do not report a version.
         * Chosen as the minimum possible revision so that any real server-assigned
         * version supersedes it.
         */
        const val DEFAULT_VERSION: Long = 0L
    }

    /**
     * Returns true if this snapshot is strictly newer than [other] and should
     * therefore be applied over it. Equal versions are considered not newer so
     * that the already-applied state is preserved (last-applied-wins for ties).
     */
    fun isNewerThan(other: Vault?): Boolean {
        if (other == null) return true
        return version > other.version
    }
}

@Serializable
enum class VaultStatus { active, expired, released, paused }

// #216: configurable threshold for the "large withdrawal" confirmation step —
// a single tunable knob rather than a literal scattered across call sites.
object WithdrawalThreshold {
    /** Percentage of the vault's balance (basis points, 10_000 = 100%) at or
     *  above which a withdrawal is considered "large" and prompts an extra
     *  confirmation step. Defaults to 80%. */
    const val LARGE_WITHDRAWAL_BPS = 8_000
}

/**
 * Whether [amount] is large enough relative to [vaultBalance] to warrant an
 * extra confirmation step before withdrawing (#216) — reduces fat-finger risk
 * on a product holding funds meant for a beneficiary. [thresholdBps] is the
 * percentage of the vault's balance (basis points, 10_000 = 100%) at or above
 * which the warning triggers; a parameter rather than hardcoded here so it
 * can be tuned via [WithdrawalThreshold] without touching call sites.
 */
fun isLargeWithdrawal(amount: Long, vaultBalance: Long, thresholdBps: Int): Boolean {
    if (vaultBalance <= 0 || amount <= 0) return false
    val ratio = amount.toDouble() / vaultBalance.toDouble()
    return ratio * 10_000 >= thresholdBps
}

// #219: status filter chips for the vault list.
enum class VaultListFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    EXPIRING_SOON("Expiring Soon"),
    EXPIRED("Expired");

    fun matches(vault: Vault): Boolean = when (this) {
        ALL -> true
        ACTIVE -> vault.status == VaultStatus.active
        EXPIRING_SOON -> vault.status == VaultStatus.active && vault.isExpiringSoon
        EXPIRED -> vault.status == VaultStatus.expired
    }
}

/**
 * Client-side search/filter over an already-fetched vault list (#219) — works
 * across every page already pulled into the vault list, since it's a pure
 * filter over that list rather than a server request.
 *
 * Filters by [statusFilter], then by [searchText] matched case-insensitively
 * against the vault's label (if set) or id. Blank [searchText] (after
 * trimming) matches everything.
 */
fun filterVaults(vaults: List<Vault>, searchText: String, statusFilter: VaultListFilter): List<Vault> {
    val statusFiltered = vaults.filter { statusFilter.matches(it) }
    val trimmed = searchText.trim()
    if (trimmed.isEmpty()) return statusFiltered
    return statusFiltered.filter { vault ->
        vault.id.contains(trimmed, ignoreCase = true) ||
            (vault.label?.contains(trimmed, ignoreCase = true) ?: false)
    }
}

// MARK: - Sessions (#208)
//
// A device currently holding a valid JWT for this account. Backs SessionsScreen's device
// list and its "Sign out this device" / "Sign out all other devices" actions.

@Serializable
data class Session(
    val id: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_active_at") val lastActiveAt: String,
    @SerialName("is_current") val isCurrent: Boolean
)

/**
 * Guards an irreversible action (delete/archive a vault, etc.) behind a typed
 * confirmation rather than a plain Yes/No tap — codified now as a guardrail
 * before any delete/archive endpoint is wired up on either client (#220),
 * given the financial and beneficiary implications of getting this wrong.
 * [requiredText] is typically the vault's own name/ID, so confirming requires
 * the user to actually read and type it back exactly. Mirrors iOS's
 * `DestructiveConfirmation` (Sources/Models/Models.swift).
 */
data class DestructiveConfirmation(
    val requiredText: String,
    val enteredText: String = ""
) {
    val isConfirmed: Boolean get() = requiredText.isNotEmpty() && enteredText == requiredText

    /**
     * Runs [action] only if [isConfirmed] — the single choke point the
     * destructive-confirmation dialog uses, so "the button is disabled" and
     * "the underlying action never fires" can't drift apart from each other.
     */
    fun confirmIfMatched(action: () -> Unit) {
        if (isConfirmed) action()
    }
}

// #109: Beneficiary acceptance request body.
// The token is parsed from the accept deep-link URL query parameter and is
// required by the server to authorise acceptance. See api-contract.md §POST /vaults/{id}/accept.
@Serializable
data class BeneficiaryAcceptRequest(
    @SerialName("vault_id") val vaultId: String,
    val token: String
)
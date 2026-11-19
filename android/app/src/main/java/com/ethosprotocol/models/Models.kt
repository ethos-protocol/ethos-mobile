package com.ethosprotocol.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.util.Locale

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

@Serializable
data class Vault(
    val id: String,
    val owner: String,
    val beneficiary: String,
    val balance: Long,
    @SerialName("check_in_interval") val checkInInterval: Long,
    @SerialName("last_check_in") val lastCheckIn: String,
    @SerialName("ttl_remaining") val ttlRemaining: Long? = null,
    val status: VaultStatus,
    // Which Stellar asset `balance` is denominated in (#222). Every vault today
    // holds native XLM; this — and assetIssuer — exist so a future non-XLM vault
    // doesn't need a breaking schema change. Absent on a server response (every
    // response today) defaults to "XLM", mirroring iOS's Vault.assetCode.
    @SerialName("asset_code") val assetCode: String = "XLM",
    // The issuing account for assetCode, or null for native XLM (mirrors
    // AcceptedAsset's convention server-side). See api-contract.md §Vault (#222).
    @SerialName("asset_issuer") val assetIssuer: String? = null,
    // Server-assigned, monotonically increasing revision used to reconcile
    // out-of-order updates (see shared/api-contract.md §Reconciling, #223 and #606).
    // Servers that do not emit it default to DEFAULT_VERSION, the lowest possible
    // revision, so an unversioned snapshot never overwrites a versioned one.
    val version: Long = DEFAULT_VERSION
) {
    companion object {
        /** Version assigned to vaults from servers that do not report a version. */
        const val DEFAULT_VERSION: Long = 0L
    }

    /**
     * True if this snapshot is strictly newer than [other] and should be applied over it.
     * Equal versions are not newer, so the already-applied state is preserved on ties.
     */
    fun isNewerThan(other: Vault?): Boolean {
        if (other == null) return true
        return version > other.version
    }

    val isExpiringSoon: Boolean get() = (ttlRemaining ?: Long.MAX_VALUE) < 86_400L

    // Assumes the 7-decimal stroop scale that applies to every Stellar classic
    // asset regardless of code — only the unit label varies (#222).
    // Uses device locale for decimal separator and digit grouping.
    val formattedBalance: String
        get() {
            val amountInAsset = balance / 10_000_000.0
            val formatter = NumberFormat.getInstance(Locale.getDefault())
            formatter.minimumFractionDigits = 7
            formatter.maximumFractionDigits = 7
            formatter.isGroupingUsed = true
            return "${formatter.format(amountInAsset)} $assetCode"
        }
}

// A single real-time event delivered over the `wss://.../ws?vault_id={id}` socket
// (see shared/api-contract.md). `vault` carries the full updated Vault so consumers
// can update state in place without an extra round trip.
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

@Serializable
data class VaultEvent(
    val type: String,
    val vault: Vault? = null
)

@Serializable
enum class VaultStatus { active, expired, released, paused }

@Serializable
data class AuthChallenge(
    val challenge: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("existing_credential_ids") val existingCredentialIds: List<String> = emptyList()
)

@Serializable
data class AuthToken(
    val token: String,
    @SerialName("expires_at") val expiresAt: String
)

@Serializable
data class CreateVaultRequest(
    val beneficiary: String,
    @SerialName("check_in_interval") val checkInInterval: Long
)

@Serializable
data class BeneficiaryUpdateRequest(val beneficiary: String)

@Serializable
data class PushRegistration(
    val token: String,
    val platform: String = "android"
)

@Serializable
data class PasskeyVerifyRequest(
    @SerialName("credential_id") val credentialId: String,
    @SerialName("client_data_json") val clientDataJson: String,
    val signature: String
)

@Serializable
data class PasskeyRegisterRequest(
    @SerialName("credential_id") val credentialId: String,
    // `public_key` is the WebAuthn COSE_Key (RFC 9052) extracted from the attestation
    // object's authData, base64url-encoded — not the raw CBOR attestation object.
    // See shared/api-contract.md's PasskeyRegisterRequest (#1) section.
    @SerialName("public_key") val publicKey: String,
    @SerialName("client_data_json") val clientDataJson: String
)

// MARK: - Account Recovery ("lost your device?")
//
// Shared contract with iOS's #5: initiate() sends a recovery code to the account's
// verified email, complete() links a newly-created passkey to that existing account once
// the recovery token proves the requester received that code.

@Serializable
data class RecoveryInitiateRequest(val username: String)

@Serializable
data class RecoveryInitiateResponse(
    @SerialName("recovery_token") val recoveryToken: String,
    @SerialName("expires_at") val expiresAt: String
)

@Serializable
data class RecoveryCompleteRequest(
    @SerialName("recovery_token") val recoveryToken: String,
    @SerialName("credential_id") val credentialId: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("client_data_json") val clientDataJson: String
)

// MARK: - 2FA Models

@Serializable
enum class TwoFactorMethod { totp, sms, email }

/**
 * #227: `availableMethods` lists the 2FA methods the server accepts for this account/vault.
 * Clients must filter the method-selection UI to only what is listed here.
 * Decoded defensively: absent field (older server) defaults to all three methods.
 */
@Serializable
data class TwoFactorStatus(
    @SerialName("vault_id") val vaultId: String,
    val enabled: Boolean,
    val method: TwoFactorMethod? = null,
    val verified: Boolean = false,
    val phone: String? = null,
    val email: String? = null,
    /** #227: Methods available for this account. Defaults to all when the server omits the field. */
    @SerialName("available_methods") val availableMethods: List<TwoFactorMethod> = TwoFactorMethod.values().toList()
)

@Serializable
data class Enable2FARequest(
    val method: TwoFactorMethod,
    val phone: String? = null,
    val email: String? = null
)

@Serializable
data class Enable2FAResponse(
    @SerialName("vault_id") val vaultId: String,
    val method: TwoFactorMethod,
    val secret: String? = null,
    @SerialName("provisioning_uri") val provisioningUri: String? = null
)

/** #226: `trustDevice` opt-in — when true the server issues a device trust token valid 30 days. */
@Serializable
data class Verify2FARequest(
    val otp: String,
    @SerialName("trust_device") val trustDevice: Boolean = false
)

/** #226: Response after verify; carries optional device trust token when opt-in was true. */
@Serializable
data class Verify2FAResponse(
    @SerialName("device_trust_token") val deviceTrustToken: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
)

// MARK: - #226 Trusted-Device Models

@Serializable
data class TrustDeviceRequest(@SerialName("trust_device") val trustDevice: Boolean = true)

@Serializable
data class TrustDeviceResponse(
    @SerialName("device_trust_token") val deviceTrustToken: String,
    @SerialName("expires_at") val expiresAt: String
)

// MARK: - #224 Backup Codes Models

@Serializable
data class BackupCodesResponse(
    val codes: List<String>,
    @SerialName("generated_at") val generatedAt: String
)

@Serializable
data class BackupCodesStatus(
    val generated: Boolean,
    @SerialName("remaining_count") val remainingCount: Int
)

// MARK: - #225 Switch 2FA Method Models

@Serializable
data class Switch2FARequest(
    @SerialName("new_method") val newMethod: TwoFactorMethod,
    val phone: String? = null,
    val email: String? = null
)

// #109: Beneficiary acceptance request body.
// The token is parsed from the accept deep-link URL query parameter and is
// required by the server to authorise acceptance. See api-contract.md §POST /vaults/{id}/accept.
@Serializable
data class BeneficiaryAcceptRequest(
    @SerialName("vault_id") val vaultId: String,
    val token: String
)

// #112: Paginated vault list response. See api-contract.md §Pagination.
@Serializable
data class VaultPage(
    val vaults: List<Vault>,
    /** Opaque cursor for the next page, or null when this is the last page. */
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean
)

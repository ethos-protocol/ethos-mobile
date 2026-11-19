package com.ethosprotocol.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val name: String,
    val version: Long = DEFAULT_VERSION
) {
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

package com.ethosprotocol.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Vault(
    val id: String,
    val owner: String,
    val beneficiary: String,
    val balance: Long,
    @SerialName("check_in_interval") val checkInInterval: Long,
    @SerialName("last_check_in") val lastCheckIn: String,
    @SerialName("ttl_remaining") val ttlRemaining: Long? = null,
    val status: VaultStatus
) {
    val isExpiringSoon: Boolean get() = (ttlRemaining ?: Long.MAX_VALUE) < 86_400L
    val formattedBalance: String get() = "%.7f XLM".format(balance / 10_000_000.0)
}

@Serializable
enum class VaultStatus { active, expired, released, paused }

@Serializable
data class AuthChallenge(
    val challenge: String,
    @SerialName("expires_at") val expiresAt: String
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
    @SerialName("public_key") val publicKey: String,
    @SerialName("client_data_json") val clientDataJson: String
)

// MARK: - 2FA Models

@Serializable
enum class TwoFactorMethod { totp, sms, email }

@Serializable
data class TwoFactorStatus(
    @SerialName("vault_id") val vaultId: String,
    val enabled: Boolean,
    val method: TwoFactorMethod? = null,
    val verified: Boolean = false,
    val phone: String? = null,
    val email: String? = null
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

@Serializable
data class Verify2FARequest(val otp: String)

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

package com.ethosprotocol.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bearer token and its ISO-8601 expiry, as returned by the auth endpoints. */
@Serializable
data class AuthToken(
    val token: String,
    @SerialName("expires_at") val expiresAt: String
)

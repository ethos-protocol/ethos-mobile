package com.ethosprotocol.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry in a vault's activity history (GET /vaults/{id}/history). */
@Serializable
data class VaultHistoryEvent(
    @SerialName("event_type") val eventType: String,
    val timestamp: String,
)

/** A page of vault activity history, using the same cursor pattern as [VaultPage]. */
@Serializable
data class VaultHistoryPage(
    val events: List<VaultHistoryEvent> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

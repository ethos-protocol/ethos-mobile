package com.ethosprotocol.services

import android.net.Uri

enum class VaultDeepLinkAction(val pathSegment: String) {
    CHECK_IN("check-in"),
    WITHDRAW("withdraw"),
    VIEW_DETAILS("view-details"),
    MANAGE_BENEFICIARY("manage-beneficiary");

    companion object {
        fun fromPathSegment(segment: String): VaultDeepLinkAction? =
            entries.find { it.pathSegment == segment }
    }
}

data class VaultDeepLink(val vaultId: String, val action: VaultDeepLinkAction)

object VaultDeepLinkParser {
    /**
     * Vault IDs are only ever used to build API request paths (e.g. "/vaults/$vaultId/checkin")
     * and Compose navigation routes, so any character outside this allowlist — in particular
     * "/", "%", "?", "#" — must be rejected here. Otherwise a crafted deep link (the custom
     * scheme intent-filter accepts input from any app on the device, unauthenticated) could
     * smuggle path segments/query parameters into requests made on the user's behalf, e.g. a
     * URI whose path segment decodes to "foo/../../other-endpoint".
     */
    private val VAULT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")

    /** True if [vaultId] is safe to interpolate into an API path / navigation route. */
    fun isValidVaultId(vaultId: String): Boolean = VAULT_ID_PATTERN.matches(vaultId)

    /** Parses ethosprotocol://vault/{vault_id}/{action} from a URL string or returns null if unrecognised. */
    fun parseUrl(url: String): VaultDeepLink? {
        val match = URL_PATTERN.matchEntire(url.trim()) ?: return null
        val vaultId = match.groupValues[1]
        if (!isValidVaultId(vaultId)) return null
        val action = VaultDeepLinkAction.fromPathSegment(match.groupValues[2]) ?: return null
        return VaultDeepLink(vaultId = vaultId, action = action)
    }

    /** Parses ethosprotocol://vault/{vault_id}/{action} from a Uri or returns null if unrecognised. */
    fun parse(uri: Uri): VaultDeepLink? {
        if (uri.scheme != "ethosprotocol" || uri.host != "vault") return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val vaultId = segments[0]
        if (!isValidVaultId(vaultId)) return null
        val action = VaultDeepLinkAction.fromPathSegment(segments[1]) ?: return null
        return VaultDeepLink(vaultId = vaultId, action = action)
    }

    private val URL_PATTERN = Regex("^ethosprotocol://vault/([^/]+)/([^/]+)$")
}

package com.ethosprotocol.services

import android.net.Uri
import android.util.Log

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

/**
 * A beneficiary-acceptance universal link
 * (https://ethos-protocol.app/vaults/{vaultId}/accept?token={token}).
 *
 * [token] proves the opener is the originally invited party and is required by
 * POST /vaults/{id}/accept — see shared/api-contract.md. It is parsed here so it can
 * be forwarded all the way into the API request body (#196).
 */
data class BeneficiaryAcceptLink(val vaultId: String, val token: String)

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

    /**
     * Fires once per successfully parsed deep link, so usage of the still-stubbed
     * WITHDRAW/MANAGE_BENEFICIARY actions can be compared against CHECK_IN/VIEW_DETAILS.
     *
     * Deliberately carries only [action] — never the vault ID or raw URI — so this can double as
     * an analytics hook without becoming a privacy-sensitive log of who opened which vault.
     *
     * Event schema (kept in sync with iOS #40 so usage is comparable cross-platform):
     *   name: "vault_deep_link_opened"
     *   properties: { action: "check-in" | "withdraw" | "view-details" | "manage-beneficiary" }
     */
    fun interface EventLogger {
        fun onDeepLinkParsed(action: VaultDeepLinkAction)
    }

    private val defaultEventLogger = EventLogger { action ->
        // android.util.Log isn't available outside an Android runtime (e.g. plain JVM unit
        // tests), and a logging failure must never break parsing — swallow and move on.
        try {
            Log.i("VaultDeepLink", "vault_deep_link_opened action=${action.pathSegment}")
        } catch (_: Throwable) {
        }
    }

    /** Overridable for tests; defaults to logging to Logcat. */
    @Volatile
    var eventLogger: EventLogger = defaultEventLogger

    /** Parses ethosprotocol://vault/{vault_id}/{action} from a URL string or returns null if unrecognised. */
    fun parseUrl(url: String): VaultDeepLink? {
        val match = URL_PATTERN.matchEntire(url.trim()) ?: return null
        val vaultId = match.groupValues[1]
        if (!isValidVaultId(vaultId)) return null
        val action = VaultDeepLinkAction.fromPathSegment(match.groupValues[2]) ?: return null
        eventLogger.onDeepLinkParsed(action)
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
        eventLogger.onDeepLinkParsed(action)
        return VaultDeepLink(vaultId = vaultId, action = action)
    }

    /**
     * Parses https://ethos-protocol.app/vaults/{vaultId}/accept?token={token}, returning both
     * the vault ID *and* the acceptance token, or null when the link is not a well-formed
     * acceptance link. A missing or invalid token yields null: dropping it would produce an
     * acceptance request the server rejects, which is what made the Android flow silently fail.
     */
    fun parseBeneficiaryAccept(uri: Uri): BeneficiaryAcceptLink? {
        if (uri.scheme != "https" || uri.host != "ethos-protocol.app") return null
        val segments = uri.pathSegments
        // Expect /vaults/{vaultId}/accept
        if (segments.size != 3 || segments[0] != "vaults" || segments[2] != "accept") return null
        val vaultId = segments[1].takeIf { isValidVaultId(it) } ?: return null
        // Same allowlist as vault IDs (alphanumerics, dash, underscore): the token is
        // interpolated into a navigation route before it reaches the API request body.
        val token = uri.getQueryParameter("token")?.takeIf { isValidVaultId(it) } ?: return null
        return BeneficiaryAcceptLink(vaultId = vaultId, token = token)
    }

    private val URL_PATTERN = Regex("^ethosprotocol://vault/([^/]+)/([^/]+)$")
}

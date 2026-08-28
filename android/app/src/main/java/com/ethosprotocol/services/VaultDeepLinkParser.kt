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

/**
 * Source channel attribution for deep link origins. Used to track which channels
 * drive check-ins, beneficiary acceptances, and other deep-link-triggered actions.
 */
enum class DeepLinkSource(val value: String) {
    PUSH_NOTIFICATION("push"),
    EMAIL("email"),
    SHARE_LINK("share"),
    WIDGET("widget"),
    UNKNOWN("unknown");

    companion object {
        fun fromString(value: String?): DeepLinkSource =
            entries.find { it.value == value } ?: UNKNOWN
    }
}

data class VaultDeepLink(val vaultId: String, val action: VaultDeepLinkAction, val source: DeepLinkSource = DeepLinkSource.UNKNOWN)

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
     * Fires once per successfully parsed deep link, carrying the action and source channel
     * for aggregate analytics. This enables attribution tracking to determine which channels
     * (push, email, share, widget) drive check-ins and other vault interactions.
     *
     * Deliberately carries only [action] and [source] — never the vault ID or raw URI — so this
     * can double as an analytics hook without becoming a privacy-sensitive log of who opened which vault.
     *
     * Event schema (kept in sync with iOS #40 so usage is comparable cross-platform):
     *   name: "vault_deep_link_opened"
     *   properties: { 
     *     action: "check-in" | "withdraw" | "view-details" | "manage-beneficiary"
     *     source: "push" | "email" | "share" | "widget" | "unknown"
     *   }
     */
    fun interface EventLogger {
        fun onDeepLinkParsed(action: VaultDeepLinkAction, source: DeepLinkSource)
    }

    private val defaultEventLogger = EventLogger { action, source ->
        // android.util.Log isn't available outside an Android runtime (e.g. plain JVM unit
        // tests), and a logging failure must never break parsing — swallow and move on.
        try {
            Log.i("VaultDeepLink", "vault_deep_link_opened action=${action.pathSegment} source=${source.value}")
        } catch (_: Throwable) {
        }
    }

    /** Overridable for tests; defaults to logging to Logcat. */
    @Volatile
    var eventLogger: EventLogger = defaultEventLogger

    /** Parses ethosprotocol://vault/{vault_id}/{action} from a URL string or returns null if unrecognised. */
    fun parseUrl(url: String, source: DeepLinkSource = DeepLinkSource.UNKNOWN): VaultDeepLink? {
        val match = URL_PATTERN.matchEntire(url.trim()) ?: return null
        val vaultId = match.groupValues[1]
        if (!isValidVaultId(vaultId)) return null
        val action = VaultDeepLinkAction.fromPathSegment(match.groupValues[2]) ?: return null
        eventLogger.onDeepLinkParsed(action, source)
        return VaultDeepLink(vaultId = vaultId, action = action, source = source)
    }

    /** Parses ethosprotocol://vault/{vault_id}/{action} from a Uri or returns null if unrecognised. */
    fun parse(uri: Uri, source: DeepLinkSource = DeepLinkSource.UNKNOWN): VaultDeepLink? {
        if (uri.scheme != "ethosprotocol" || uri.host != "vault") return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val vaultId = segments[0]
        if (!isValidVaultId(vaultId)) return null
        val action = VaultDeepLinkAction.fromPathSegment(segments[1]) ?: return null
        eventLogger.onDeepLinkParsed(action, source)
        return VaultDeepLink(vaultId = vaultId, action = action, source = source)
    }

    private val URL_PATTERN = Regex("^ethosprotocol://vault/([^/]+)/([^/]+)$")
}

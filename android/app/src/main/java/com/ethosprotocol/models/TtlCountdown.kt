package com.ethosprotocol.models

/**
 * Client-side countdown derived from a server-provided TTL snapshot (`GET
 * /vaults/{id}/ttl`, or the `ttl_remaining` field on a poll/`vault_updated`
 * push), ticked locally between refreshes so a displayed countdown counts down
 * in real time instead of visibly freezing until the next refresh (#221).
 *
 * Mirrors iOS's `TTLCountdown` (Sources/Models/Models.swift) so both platforms
 * apply the same reconciliation rule (#223).
 */
data class TtlCountdown(
    /** The TTL value (seconds remaining) last reported by the server. */
    val serverValue: Long,
    /** When [serverValue] was fetched, in epoch millis — the ticking baseline. */
    val fetchedAtMillis: Long
) {
    /**
     * Seconds remaining as of [nowMillis], ticking down from [serverValue].
     * Never goes below zero, even once the local tick has run past a stale
     * server value.
     */
    fun remaining(nowMillis: Long): Long {
        val elapsedSeconds = (nowMillis - fetchedAtMillis) / 1000
        if (elapsedSeconds <= 0) return serverValue
        val remaining = serverValue - elapsedSeconds
        return if (remaining > 0) remaining else 0
    }

    /**
     * Reconciles with a fresh server value, from either a poll or a
     * `vault_updated` push — both are treated identically. The server value
     * always wins over wherever the local tick has drifted to: this replaces
     * the baseline outright rather than comparing against it.
     */
    fun reconcile(serverValue: Long, nowMillis: Long): TtlCountdown =
        TtlCountdown(serverValue = serverValue, fetchedAtMillis = nowMillis)
}

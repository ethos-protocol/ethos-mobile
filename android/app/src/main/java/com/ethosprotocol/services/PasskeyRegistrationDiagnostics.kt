package com.ethosprotocol.services

import java.util.Collections

/**
 * Logs client-side diagnostic signal for passkey registration failures, for support triage
 * of "passkey sign-in doesn't work" reports. Mirrors iOS's `PasskeyDiagnosticsLogger`.
 *
 * Only the authenticator attachment type and attestation format are recorded — see
 * SECURITY.md for the full scope of what is/isn't logged. No public key material,
 * signatures, challenge bytes, or credential IDs are ever logged here.
 */
object PasskeyRegistrationDiagnostics {

    data class Entry(
        val authenticatorAttachment: String,
        val attestationFormat: String?,
        val reason: String,
        val timestampMillis: Long
    )

    private val eventLog: MutableList<Entry> = Collections.synchronizedList(mutableListOf())

    /**
     * Logs a registration failure. [attestationFormat] is the WebAuthn `fmt` value (e.g.
     * "packed", "none") when it could be parsed from the attestation object, or `null` when
     * the ceremony failed before one was produced.
     */
    fun logFailure(authenticatorAttachment: String, attestationFormat: String?, reason: String, now: Long = System.currentTimeMillis()) {
        eventLog.add(Entry(authenticatorAttachment, attestationFormat, reason, now))
    }

    fun getLoggedEvents(): List<Entry> = synchronized(eventLog) { eventLog.toList() }

    fun clearLog() {
        eventLog.clear()
    }
}

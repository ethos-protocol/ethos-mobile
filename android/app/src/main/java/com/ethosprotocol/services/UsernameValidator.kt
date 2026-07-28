package com.ethosprotocol.services

/**
 * Provisional rule pending confirmation against the backend's own validation
 * and iOS issue #11 — this must stay identical across both clients since the
 * value is used verbatim to build the WebAuthn user.id/user.name fields.
 */
object UsernameValidator {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 32

    private val PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{${MIN_LENGTH - 2},${MAX_LENGTH - 2}}[A-Za-z0-9]$")

    fun sanitize(raw: String): String = raw.trim()

    fun isValid(raw: String): Boolean = PATTERN.matches(sanitize(raw))
}

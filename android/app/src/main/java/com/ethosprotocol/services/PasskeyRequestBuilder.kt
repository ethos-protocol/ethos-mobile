package com.ethosprotocol.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Pure WebAuthn requestJson construction for [PasskeyService], kept separate so its
 * exact shape (key names, rp/user nesting, authenticatorSelection flags) can be
 * unit-tested without a live CredentialManager.
 */
internal object PasskeyRequestBuilder {
    private const val RP_ID = "ethos-protocol.app"
    private const val RP_NAME = "Ethos-Protocol"

    fun registrationRequestJson(challenge: String, username: String): String =
        registrationRequest(challenge, username).toString()

    fun authenticationRequestJson(challenge: String): String =
        authenticationRequest(challenge).toString()

    internal fun registrationRequest(challenge: String, username: String): JsonObject = buildJsonObject {
        put("challenge", challenge)
        putJsonObject("rp") {
            put("id", RP_ID)
            put("name", RP_NAME)
        }
        putJsonObject("user") {
            put("id", Base64.getUrlEncoder().withoutPadding().encodeToString(username.toByteArray()))
            put("name", username)
            put("displayName", username)
        }
        putJsonArray("pubKeyCredParams") {
            add(buildJsonObject {
                put("type", "public-key")
                put("alg", -7)
            })
        }
        putJsonObject("authenticatorSelection") {
            put("authenticatorAttachment", "platform")
            put("requireResidentKey", true)
            put("userVerification", "required")
        }
    }

    internal fun authenticationRequest(challenge: String): JsonObject = buildJsonObject {
        put("challenge", challenge)
        put("rpId", RP_ID)
        put("userVerification", "required")
    }
}

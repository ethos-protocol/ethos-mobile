package com.ethosprotocol

import com.ethosprotocol.models.AuthChallenge
import com.ethosprotocol.models.AuthToken
import com.ethosprotocol.models.PasskeyRegisterRequest
import com.ethosprotocol.models.PasskeyVerifyRequest
import com.ethosprotocol.models.RecoveryCompleteRequest
import com.ethosprotocol.models.RecoveryInitiateRequest
import com.ethosprotocol.models.RecoveryInitiateResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryAndPasskeyModelsJsonTest {

    private val json = Json

    @Test
    fun `AuthChallenge defaults existing_credential_ids to an empty list`() {
        val challenge = json.decodeFromString<AuthChallenge>(
            """{"challenge":"c-1","expires_at":"2030-01-01T00:00:00Z"}"""
        )

        assertTrue(challenge.existingCredentialIds.isEmpty())
    }

    @Test
    fun `AuthToken reads token and expires_at`() {
        val token = json.decodeFromString<AuthToken>(
            """{"token":"t","expires_at":"2030-01-01T00:00:00Z"}"""
        )

        assertEquals(AuthToken("t", "2030-01-01T00:00:00Z"), token)
    }

    @Test
    fun `PasskeyVerifyRequest uses snake_case wire names`() {
        val encoded = json.parseToJsonElement(
            json.encodeToString(PasskeyVerifyRequest("cred", "cdj", "sig"))
        ).jsonObject

        assertEquals("cred", encoded["credential_id"]!!.jsonPrimitive.content)
        assertEquals("cdj", encoded["client_data_json"]!!.jsonPrimitive.content)
        assertEquals("sig", encoded["signature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PasskeyRegisterRequest round-trips with public_key`() {
        val original = PasskeyRegisterRequest(credentialId = "cred", publicKey = "pk", clientDataJson = "cdj")

        assertEquals(original, json.decodeFromString<PasskeyRegisterRequest>(json.encodeToString(original)))
    }

    @Test
    fun `RecoveryInitiateRequest encodes username`() {
        val encoded = json.parseToJsonElement(json.encodeToString(RecoveryInitiateRequest("alice"))).jsonObject

        assertEquals("alice", encoded["username"]!!.jsonPrimitive.content)
    }

    @Test
    fun `RecoveryInitiateResponse reads recovery_token and expires_at`() {
        val response = json.decodeFromString<RecoveryInitiateResponse>(
            """{"recovery_token":"rt","expires_at":"2030-01-01T00:00:00Z"}"""
        )

        assertEquals(RecoveryInitiateResponse("rt", "2030-01-01T00:00:00Z"), response)
    }

    @Test
    fun `RecoveryCompleteRequest uses snake_case wire names`() {
        val original = RecoveryCompleteRequest(
            recoveryToken = "rt",
            credentialId = "cred",
            publicKey = "pk",
            clientDataJson = "cdj"
        )

        val encoded = json.parseToJsonElement(json.encodeToString(original)).jsonObject

        assertEquals("rt", encoded["recovery_token"]!!.jsonPrimitive.content)
        assertEquals("pk", encoded["public_key"]!!.jsonPrimitive.content)
        assertEquals(original, json.decodeFromString<RecoveryCompleteRequest>(json.encodeToString(original)))
    }
}

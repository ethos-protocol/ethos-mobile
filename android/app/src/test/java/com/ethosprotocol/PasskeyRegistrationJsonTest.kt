package com.ethosprotocol

import com.ethosprotocol.models.AuthChallenge
import com.ethosprotocol.services.buildRegistrationRequestJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PasskeyRegistrationJsonTest {

    @Test
    fun `omits excludeCredentials when challenge has no existing credentials`() {
        val challenge = AuthChallenge(challenge = "c-1", expiresAt = "2026-08-01T00:00:00Z")

        val json = JSONObject(buildRegistrationRequestJson(challenge, "alice"))

        assertFalse(json.has("excludeCredentials"))
    }

    @Test
    fun `includes excludeCredentials with existing credential ids when provided`() {
        val challenge = AuthChallenge(
            challenge = "c-1",
            expiresAt = "2026-08-01T00:00:00Z",
            existingCredentialIds = listOf("cred-1", "cred-2")
        )

        val json = JSONObject(buildRegistrationRequestJson(challenge, "alice"))
        val excludeCredentials = json.getJSONArray("excludeCredentials")

        assertEquals(2, excludeCredentials.length())
        assertEquals("public-key", excludeCredentials.getJSONObject(0).getString("type"))
        assertEquals("cred-1", excludeCredentials.getJSONObject(0).getString("id"))
        assertEquals("public-key", excludeCredentials.getJSONObject(1).getString("type"))
        assertEquals("cred-2", excludeCredentials.getJSONObject(1).getString("id"))
    }

    @Test
    fun `still includes the base registration fields`() {
        val challenge = AuthChallenge(challenge = "c-1", expiresAt = "2026-08-01T00:00:00Z")

        val json = JSONObject(buildRegistrationRequestJson(challenge, "alice"))

        assertEquals("c-1", json.getString("challenge"))
        assertEquals("ethos-protocol.app", json.getJSONObject("rp").getString("id"))
        assertEquals("alice", json.getJSONObject("user").getString("name"))
    }
}

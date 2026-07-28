package com.ethosprotocol

import com.ethosprotocol.services.PasskeyRequestBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PasskeyRequestBuilderTest {

    @Test
    fun registrationRequest_hasExactTopLevelKeys() {
        val request = PasskeyRequestBuilder.registrationRequest("chal-123", "alice")
        assertEquals(
            setOf("challenge", "rp", "user", "pubKeyCredParams", "authenticatorSelection"),
            request.keys
        )
    }

    @Test
    fun registrationRequest_setsChallengeVerbatim() {
        val request = PasskeyRequestBuilder.registrationRequest("chal-123", "alice")
        assertEquals("chal-123", request["challenge"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun registrationRequest_rpIsEthosProtocolDomain() {
        val rp = PasskeyRequestBuilder.registrationRequest("chal-123", "alice")["rp"]!!.jsonObject
        assertEquals(setOf("id", "name"), rp.keys)
        assertEquals("ethos-protocol.app", rp["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Ethos-Protocol", rp["name"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun registrationRequest_userIdIsBase64UrlOfUsername() {
        val user = PasskeyRequestBuilder.registrationRequest("chal-123", "alice")["user"]!!.jsonObject
        assertEquals(setOf("id", "name", "displayName"), user.keys)
        val expectedId = Base64.getUrlEncoder().withoutPadding().encodeToString("alice".toByteArray())
        assertEquals(expectedId, user["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("alice", user["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("alice", user["displayName"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun registrationRequest_pubKeyCredParamsUsesEs256() {
        val params = PasskeyRequestBuilder.registrationRequest("chal-123", "alice")["pubKeyCredParams"]!!.jsonArray
        assertEquals(1, params.size)
        val param = params[0].jsonObject
        assertEquals("public-key", param["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(-7, param["alg"]?.jsonPrimitive?.int)
    }

    @Test
    fun registrationRequest_authenticatorSelectionRequiresPlatformResidentKeyAndUserVerification() {
        val selection = PasskeyRequestBuilder.registrationRequest("chal-123", "alice")["authenticatorSelection"]!!.jsonObject
        assertEquals(setOf("authenticatorAttachment", "requireResidentKey", "userVerification"), selection.keys)
        assertEquals("platform", selection["authenticatorAttachment"]?.jsonPrimitive?.contentOrNull)
        assertTrue(selection["requireResidentKey"]?.jsonPrimitive?.boolean == true)
        assertEquals("required", selection["userVerification"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun authenticationRequest_hasExactShape() {
        val request = PasskeyRequestBuilder.authenticationRequest("chal-456")
        assertEquals(setOf("challenge", "rpId", "userVerification"), request.keys)
        assertEquals("chal-456", request["challenge"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ethos-protocol.app", request["rpId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("required", request["userVerification"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun registrationRequestJson_isValidJsonMatchingRegistrationRequest() {
        val json = PasskeyRequestBuilder.registrationRequestJson("chal-123", "alice")
        assertEquals(PasskeyRequestBuilder.registrationRequest("chal-123", "alice").toString(), json)
    }

    @Test
    fun authenticationRequestJson_isValidJsonMatchingAuthenticationRequest() {
        val json = PasskeyRequestBuilder.authenticationRequestJson("chal-456")
        assertEquals(PasskeyRequestBuilder.authenticationRequest("chal-456").toString(), json)
    }
}

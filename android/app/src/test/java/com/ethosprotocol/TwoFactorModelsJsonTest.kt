package com.ethosprotocol

import com.ethosprotocol.models.BackupCodesResponse
import com.ethosprotocol.models.BackupCodesStatus
import com.ethosprotocol.models.Enable2FARequest
import com.ethosprotocol.models.Enable2FAResponse
import com.ethosprotocol.models.Switch2FARequest
import com.ethosprotocol.models.TrustDeviceRequest
import com.ethosprotocol.models.TrustDeviceResponse
import com.ethosprotocol.models.TwoFactorMethod
import com.ethosprotocol.models.TwoFactorStatus
import com.ethosprotocol.models.Verify2FARequest
import com.ethosprotocol.models.Verify2FAResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFactorModelsJsonTest {

    private val json = Json

    @Test
    fun `TwoFactorStatus defaults available_methods to all methods when absent`() {
        val status = json.decodeFromString<TwoFactorStatus>(
            """{"vault_id":"v1","enabled":true}"""
        )

        assertEquals(TwoFactorMethod.values().toList(), status.availableMethods)
    }

    @Test
    fun `TwoFactorStatus decodes the server available_methods list`() {
        val status = json.decodeFromString<TwoFactorStatus>(
            """{"vault_id":"v1","enabled":true,"method":"totp","available_methods":["totp","email"]}"""
        )

        assertEquals(listOf(TwoFactorMethod.totp, TwoFactorMethod.email), status.availableMethods)
        assertEquals(TwoFactorMethod.totp, status.method)
    }

    @Test
    fun `Enable2FAResponse reads provisioning_uri and vault_id`() {
        val response = json.decodeFromString<Enable2FAResponse>(
            """{"vault_id":"v1","method":"totp","secret":"JBSWY3DP","provisioning_uri":"otpauth://totp/Ethos?secret=JBSWY3DP"}"""
        )

        assertEquals("v1", response.vaultId)
        assertEquals("otpauth://totp/Ethos?secret=JBSWY3DP", response.provisioningUri)
    }

    @Test
    fun `Verify2FARequest encodes trust_device and defaults it to false`() {
        val encoded = json.parseToJsonElement(json.encodeToString(Verify2FARequest(otp = "123456")))

        assertFalse(encoded.jsonObject["trust_device"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("123456", encoded.jsonObject["otp"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Verify2FAResponse round-trips the device trust token`() {
        val original = Verify2FAResponse(deviceTrustToken = "tok", expiresAt = "2030-01-01T00:00:00Z")

        val decoded = json.decodeFromString<Verify2FAResponse>(json.encodeToString(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `TrustDeviceRequest defaults trust_device to true`() {
        val encoded = json.parseToJsonElement(json.encodeToString(TrustDeviceRequest()))

        assertTrue(encoded.jsonObject["trust_device"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `TrustDeviceResponse round-trips`() {
        val original = TrustDeviceResponse(deviceTrustToken = "tok", expiresAt = "2030-01-01T00:00:00Z")

        assertEquals(original, json.decodeFromString<TrustDeviceResponse>(json.encodeToString(original)))
    }

    @Test
    fun `BackupCodesResponse round-trips with its code list`() {
        val original = BackupCodesResponse(codes = listOf("a1", "b2"), generatedAt = "2030-01-01T00:00:00Z")

        assertEquals(original, json.decodeFromString<BackupCodesResponse>(json.encodeToString(original)))
    }

    @Test
    fun `BackupCodesStatus reads remaining_count`() {
        val status = json.decodeFromString<BackupCodesStatus>("""{"generated":true,"remaining_count":7}""")

        assertEquals(BackupCodesStatus(generated = true, remainingCount = 7), status)
    }

    @Test
    fun `Switch2FARequest encodes new_method`() {
        val encoded = json.parseToJsonElement(
            json.encodeToString(Switch2FARequest(newMethod = TwoFactorMethod.email, email = "a@b.c"))
        )

        assertEquals("email", encoded.jsonObject["new_method"]!!.jsonPrimitive.content)
        assertEquals("a@b.c", encoded.jsonObject["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Enable2FARequest round-trips the method and phone`() {
        val original = Enable2FARequest(method = TwoFactorMethod.sms, phone = "+15550100")

        assertEquals(original, json.decodeFromString<Enable2FARequest>(json.encodeToString(original)))
    }
}

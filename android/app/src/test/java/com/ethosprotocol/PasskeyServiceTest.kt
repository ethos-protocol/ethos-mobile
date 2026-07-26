package com.ethosprotocol

import android.app.Activity
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.AuthChallenge
import com.ethosprotocol.models.AuthToken
import com.ethosprotocol.models.PasskeyRegisterRequest
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.services.extractCosePublicKey
import io.mockk.capture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PasskeyServiceTest {

    private val apiClient: ApiClient = mockk()
    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val credentialManager: CredentialManager = mockk()
    private val activity: Activity = mockk(relaxed = true)
    private val service = PasskeyService(apiClient, tokenProvider, credentialManager)

    private val coseKeyBytes = cborEs256CoseKey(x = ByteArray(32) { 0x01 }, y = ByteArray(32) { 0x02 })
    private val credentialIdBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

    @Test
    fun `register performs exactly one CredentialManager ceremony and stores the returned session token`() = runTest {
        val challenge = AuthChallenge(challenge = "c-1", expiresAt = "2026-08-01T00:00:00Z")
        coEvery { apiClient.getChallenge() } returns ApiResult.Success(challenge)

        val response: CreatePublicKeyCredentialResponse = mockk()
        every { response.registrationResponseJson } returns registrationResponseJson()
        coEvery { credentialManager.createCredential(any(), any()) } returns
                response as CreateCredentialResponse

        val authToken = AuthToken(token = "session-token", expiresAt = "2026-08-01T00:10:00Z")
        coEvery { apiClient.registerPasskey(any()) } returns ApiResult.Success(authToken)

        val result = service.register(activity, "alice")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { credentialManager.createCredential(any(), any()) }
        coVerify(exactly = 0) { credentialManager.getCredential(any(), any()) }
        verify(exactly = 1) { tokenProvider.setSession(authToken) }
    }

    @Test
    fun `register sends the extracted COSE public key, not the raw attestation object, as public_key`() = runTest {
        val challenge = AuthChallenge(challenge = "c-1", expiresAt = "2026-08-01T00:00:00Z")
        coEvery { apiClient.getChallenge() } returns ApiResult.Success(challenge)

        val response: CreatePublicKeyCredentialResponse = mockk()
        every { response.registrationResponseJson } returns registrationResponseJson()
        coEvery { credentialManager.createCredential(any(), any()) } returns
                response as CreateCredentialResponse

        val authToken = AuthToken(token = "session-token", expiresAt = "2026-08-01T00:10:00Z")
        val sentRequest = slot<PasskeyRegisterRequest>()
        coEvery { apiClient.registerPasskey(capture(sentRequest)) } returns ApiResult.Success(authToken)

        service.register(activity, "alice")

        assertEquals("cred-id-1", sentRequest.captured.credentialId)
        val sentPublicKey = Base64.getUrlDecoder().decode(sentRequest.captured.publicKey)
        assertArrayEquals(coseKeyBytes, sentPublicKey)
    }

    @Test
    fun `extractCosePublicKey pulls just the COSE key out of the attestation object`() {
        val authData = cborAuthData(credentialIdBytes, coseKeyBytes)
        val attestationObjectB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(cborAttestationObject(authData))

        val extracted = Base64.getUrlDecoder().decode(extractCosePublicKey(attestationObjectB64))

        assertArrayEquals(coseKeyBytes, extracted)
    }

    private fun registrationResponseJson(): String {
        val authData = cborAuthData(credentialIdBytes, coseKeyBytes)
        val attestationObjectB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(cborAttestationObject(authData))
        return JSONObject()
            .put("id", "cred-id-1")
            .put(
                "response",
                JSONObject()
                    .put("attestationObject", attestationObjectB64)
                    .put("clientDataJSON", "dummy-client-data")
            )
            .toString()
    }
}

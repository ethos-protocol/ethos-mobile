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
import com.ethosprotocol.services.PasskeyService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyServiceTest {

    private val apiClient: ApiClient = mockk()
    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val credentialManager: CredentialManager = mockk()
    private val activity: Activity = mockk(relaxed = true)
    private val service = PasskeyService(apiClient, tokenProvider, credentialManager)

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
        verify(exactly = 1) { tokenProvider.token = "session-token" }
    }

    private fun registrationResponseJson(): String =
        JSONObject()
            .put("id", "cred-id-1")
            .put(
                "response",
                JSONObject()
                    .put("attestationObject", "dummy-attestation-object")
                    .put("clientDataJSON", "dummy-client-data")
            )
            .toString()
}

package com.ethosprotocol.services

import android.app.Activity
import androidx.credentials.*
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.PasskeyRegisterRequest
import com.ethosprotocol.models.PasskeyVerifyRequest
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PasskeyService @Inject constructor(
    private val apiClient: ApiClient,
    private val tokenProvider: TokenProvider
) {
    suspend fun register(activity: Activity, username: String): Result<Unit> = runCatching {
        val normalizedUsername = UsernameValidator.sanitize(username)
        require(UsernameValidator.isValid(normalizedUsername)) { "Invalid username" }
        val challenge = requireSuccess(apiClient.getChallenge()).challenge
        val requestJson = PasskeyRequestBuilder.registrationRequestJson(challenge, normalizedUsername)

        val credManager = CredentialManager.create(activity)
        val resp = credManager.createCredential(activity, CreatePublicKeyCredentialRequest(requestJson))
                as CreatePublicKeyCredentialResponse
        val json = JSONObject(resp.registrationResponseJson)
        val regReq = PasskeyRegisterRequest(
            credentialId = json.getString("id"),
            publicKey = json.getJSONObject("response").getString("attestationObject"),
            clientDataJson = json.getJSONObject("response").getString("clientDataJSON")
        )
        requireSuccess(apiClient.registerPasskey(regReq))
    }

    suspend fun authenticate(activity: Activity): Result<Unit> = runCatching {
        val challenge = requireSuccess(apiClient.getChallenge()).challenge
        val requestJson = PasskeyRequestBuilder.authenticationRequestJson(challenge)

        val credManager = CredentialManager.create(activity)
        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))
        val credential = credManager.getCredential(activity, request).credential as PublicKeyCredential
        val json = JSONObject(credential.authenticationResponseJson)
        val verifyReq = PasskeyVerifyRequest(
            credentialId = json.getString("id"),
            clientDataJson = json.getJSONObject("response").getString("clientDataJSON"),
            signature = json.getJSONObject("response").getString("signature")
        )
        tokenProvider.token = requireSuccess(apiClient.verifyPasskey(verifyReq)).token
    }

    private fun <T> requireSuccess(result: ApiResult<T>): T {
        return when (result) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> error(result.message)
            ApiResult.NetworkUnavailable -> error("No network connection")
        }
    }
}

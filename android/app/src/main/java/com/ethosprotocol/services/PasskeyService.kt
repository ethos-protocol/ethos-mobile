package com.ethosprotocol.services

import android.app.Activity
import androidx.credentials.*
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.AuthChallenge
import com.ethosprotocol.models.PasskeyRegisterRequest
import com.ethosprotocol.models.PasskeyVerifyRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private const val RP_ID = "ethos-protocol.app"

@Singleton
class PasskeyService @Inject constructor(
    private val apiClient: ApiClient,
    private val tokenProvider: TokenProvider,
    private val credentialManager: CredentialManager
) {
    suspend fun register(activity: Activity, username: String): Result<Unit> = runCatching {
        val challenge = requireSuccess(apiClient.getChallenge())
        val requestJson = buildRegistrationRequestJson(challenge, username)

        val resp = credentialManager.createCredential(activity, CreatePublicKeyCredentialRequest(requestJson))
                as CreatePublicKeyCredentialResponse
        val json = JSONObject(resp.registrationResponseJson)
        val regReq = PasskeyRegisterRequest(
            credentialId = json.getString("id"),
            publicKey = json.getJSONObject("response").getString("attestationObject"),
            clientDataJson = json.getJSONObject("response").getString("clientDataJSON")
        )
        // The backend returns a session token straight from registration, so there's no
        // need to immediately run a second CredentialManager ceremony (and second
        // biometric prompt) just to sign in with the passkey we just created.
        val authToken = requireSuccess(apiClient.registerPasskey(regReq))
        tokenProvider.token = authToken.token
    }

    suspend fun authenticate(activity: Activity): Result<Unit> = runCatching {
        val challenge = requireSuccess(apiClient.getChallenge())
        val requestJson = JSONObject()
            .put("challenge", challenge.challenge).put("rpId", RP_ID)
            .put("userVerification", "required").toString()

        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))
        val credential = credentialManager.getCredential(activity, request).credential as PublicKeyCredential
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

// Top-level (rather than private to the class) and `internal` so PasskeyServiceTest can
// verify the WebAuthn JSON shape directly, without driving a real CredentialManager ceremony.
internal fun buildRegistrationRequestJson(challenge: AuthChallenge, username: String): String =
    JSONObject().apply {
        put("challenge", challenge.challenge)
        put("rp", JSONObject().put("id", RP_ID).put("name", "Ethos-Protocol"))
        put("user", JSONObject()
            .put("id", Base64.getUrlEncoder().withoutPadding().encodeToString(username.toByteArray()))
            .put("name", username).put("displayName", username))
        put("pubKeyCredParams", JSONArray().put(JSONObject().put("type", "public-key").put("alg", -7)))
        put("authenticatorSelection", JSONObject()
            .put("authenticatorAttachment", "platform")
            .put("requireResidentKey", true)
            .put("userVerification", "required"))
        // Without this, CredentialManager has no way to know the user already has a
        // passkey for this account, so it happily creates a second one with no warning.
        if (challenge.existingCredentialIds.isNotEmpty()) {
            put("excludeCredentials", JSONArray(challenge.existingCredentialIds.map {
                JSONObject().put("type", "public-key").put("id", it)
            }))
        }
    }.toString()

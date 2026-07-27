package com.ethosprotocol.services

import android.app.Activity
import androidx.credentials.*
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.PasskeyRegisterRequest
import com.ethosprotocol.models.PasskeyVerifyRequest
import com.ethosprotocol.models.RecoveryCompleteRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Carries a message already mapped to something actionable for [AuthUiState.error]. */
class PasskeyException(message: String) : Exception(message)

@Singleton
class PasskeyService @Inject constructor(
    private val apiClient: ApiClient,
    private val tokenProvider: TokenProvider
) {
    suspend fun register(activity: Activity, username: String): Result<Unit> = runCatching {
        val json = createPasskeyCredential(activity, username)
        val regReq = PasskeyRegisterRequest(
            credentialId = json.getString("id"),
            publicKey = json.getJSONObject("response").getString("attestationObject"),
            clientDataJson = json.getJSONObject("response").getString("clientDataJSON")
        )
        requireSuccess(apiClient.registerPasskey(regReq))
    }

    // Links a freshly-created passkey to an existing account for a user who lost their
    // original device — the recovery token proves they completed initiateRecovery() first.
    suspend fun recoverAccount(activity: Activity, username: String, recoveryToken: String): Result<Unit> = runCatching {
        val json = createPasskeyCredential(activity, username)
        val completeReq = RecoveryCompleteRequest(
            recoveryToken = recoveryToken,
            credentialId = json.getString("id"),
            publicKey = json.getJSONObject("response").getString("attestationObject"),
            clientDataJson = json.getJSONObject("response").getString("clientDataJSON")
        )
        requireSuccess(apiClient.completeRecovery(completeReq))
    }

    private suspend fun createPasskeyCredential(activity: Activity, username: String): JSONObject {
        val challenge = requireSuccess(apiClient.getChallenge()).challenge
        val requestJson = buildCreateCredentialRequestJson(challenge, username)

        val credManager = CredentialManager.create(activity)
        val resp = try {
            credManager.createCredential(activity, CreatePublicKeyCredentialRequest(requestJson))
                    as CreatePublicKeyCredentialResponse
        } catch (e: CreateCredentialException) {
            throw PasskeyException(mapCreateCredentialError(e))
        }
        return JSONObject(resp.registrationResponseJson)
    }

    suspend fun authenticate(activity: Activity): Result<Unit> = runCatching {
        val challenge = requireSuccess(apiClient.getChallenge()).challenge
        val requestJson = JSONObject()
            .put("challenge", challenge).put("rpId", "ethos-protocol.app")
            .put("userVerification", "required").toString()

        val credManager = CredentialManager.create(activity)
        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))
        val credential = try {
            credManager.getCredential(activity, request).credential as PublicKeyCredential
        } catch (e: GetCredentialException) {
            throw PasskeyException(mapGetCredentialError(e))
        }
        val json = JSONObject(credential.authenticationResponseJson)
        val verifyReq = PasskeyVerifyRequest(
            credentialId = json.getString("id"),
            clientDataJson = json.getJSONObject("response").getString("clientDataJSON"),
            signature = json.getJSONObject("response").getString("signature")
        )
        tokenProvider.token = requireSuccess(apiClient.verifyPasskey(verifyReq)).token
    }

    private fun buildCreateCredentialRequestJson(challenge: String, username: String): String =
        JSONObject().apply {
            put("challenge", challenge)
            put("rp", JSONObject().put("id", "ethos-protocol.app").put("name", "Ethos-Protocol"))
            put("user", JSONObject()
                .put("id", Base64.getUrlEncoder().withoutPadding().encodeToString(username.toByteArray()))
                .put("name", username).put("displayName", username))
            put("pubKeyCredParams", JSONArray().put(JSONObject().put("type", "public-key").put("alg", -7)))
            put("authenticatorSelection", JSONObject()
                .put("authenticatorAttachment", "platform")
                .put("requireResidentKey", true)
                .put("userVerification", "required"))
        }.toString()

    // Every CredentialManager failure previously collapsed into one generic message. Map the
    // subtypes that carry actionable meaning; anything else falls back to a generic retry prompt.
    private fun mapCreateCredentialError(e: CreateCredentialException): String = when (e) {
        is CreateCredentialCancellationException -> "Sign-up canceled."
        is CreateCredentialNoCreateOptionException ->
            "This device can't create a passkey. Add a screen lock in Settings and try again."
        is CreateCredentialProviderConfigurationException ->
            "No passkey provider is set up on this device."
        is CreateCredentialInterruptedException -> "Setup was interrupted — please try again."
        else -> "Couldn't create a passkey on this device. Please try again."
    }

    private fun mapGetCredentialError(e: GetCredentialException): String = when (e) {
        is GetCredentialCancellationException -> "Sign-in canceled."
        is NoCredentialException ->
            "No passkey found for this account on this device. Create an account or use the device you registered with."
        is GetCredentialProviderConfigurationException ->
            "No passkey provider is set up on this device."
        is GetCredentialInterruptedException -> "Sign-in was interrupted — please try again."
        else -> "Couldn't sign in with a passkey. Please try again."
    }

    private fun <T> requireSuccess(result: ApiResult<T>): T {
        return when (result) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> error(result.message)
            ApiResult.NetworkUnavailable -> error("No network connection")
        }
    }
}

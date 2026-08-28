package com.ethosprotocol

import android.app.Activity
import androidx.credentials.*
import com.ethosprotocol.api.ApiCallFailedException
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.AddPasskeyRequest
import com.ethosprotocol.models.AuthChallenge
import com.ethosprotocol.models.AuthToken
import com.ethosprotocol.models.PasskeyCredential
import com.ethosprotocol.models.PasskeyRegisterRequest
import com.ethosprotocol.models.PasskeyVerifyRequest
import com.ethosprotocol.services.CredentialManagerFactory
import com.ethosprotocol.services.PasskeyService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PasskeyService] (#95).
 *
 * [CredentialManager] is injected via [CredentialManagerFactory] so these tests run on the
 * JVM without Robolectric.  The [Activity] parameter is only forwarded to the factory and
 * manager; tests pass a MockK relaxed mock for it.
 *
 * Coverage:
 *   - register: success path (challenge → createCredential → registerPasskey)
 *   - register: CredentialManager throws → Result.isFailure, registerPasskey not called
 *   - register: getChallenge returns NetworkUnavailable → Result.isFailure
 *   - register: registerPasskey returns ApiResult.Error → Result.isFailure
 *   - authenticate: success path → tokenProvider.token is set
 *   - authenticate: getChallenge NetworkUnavailable → Result.isFailure, token unchanged
 *   - authenticate: getCredential throws → Result.isFailure, token unchanged
 *   - authenticate: verifyPasskey ApiResult.Error → Result.isFailure, token unchanged
 *   - authenticate: verifyPasskey NetworkUnavailable → Result.isFailure, token unchanged
 *   - requireSuccess: Success → returns data
 *   - requireSuccess: Error → throws with message
 *   - requireSuccess: NetworkUnavailable → throws with "No network connection"
 */
class PasskeyServiceTest {

    // ── mocks ─────────────────────────────────────────────────────────────────
    private val mockApiClient: ApiClient = mockk()
    private val mockCredentialManager: CredentialManager = mockk()
    private val mockActivity: Activity = mockk(relaxed = true)

    /** In-memory token holder matching the real [TokenProvider] contract. */
    private val tokenProvider = object : TokenProvider {
        override var token: String? = null
        override fun clear() { token = null }
    }

    private lateinit var service: PasskeyService

    // ── test data ─────────────────────────────────────────────────────────────

    private val fakeChallenge = AuthChallenge(
        challenge = "ch4ll3ng3==",
        expiresAt = "2099-01-01T00:00:00Z"
    )
    private val fakeToken = AuthToken(
        token = "jwt.token.value",
        expiresAt = "2099-01-01T00:00:00Z"
    )

    // extractCosePublicKey() actually base64url-decodes and CBOR-parses attestationObject,
    // so the fixture needs to be real CBOR bytes (via CborTestUtils), not a placeholder
    // string — a bogus string fails to decode before registerPasskey() is ever reached.
    private val fakeCoseKey = cborEs256CoseKey(x = ByteArray(32) { 0x11 }, y = ByteArray(32) { 0x22 })
    private val fakeAttestationObject = cborAttestationObject(
        cborAuthData(credentialId = byteArrayOf(0xCA.toByte(), 0xFE.toByte()), coseKeyBytes = fakeCoseKey)
    )
    private val fakeCoseKeyBase64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(fakeCoseKey)
    private val fakeAttestationObjectBase64 =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(fakeAttestationObject)

    /** Minimal valid WebAuthn registration response JSON. */
    private val fakeRegistrationJson = """
        {
          "id": "credential-id-reg",
          "response": {
            "attestationObject": "$fakeAttestationObjectBase64",
            "clientDataJSON": "client-data-base64"
          }
        }
    """.trimIndent()

    /** Minimal valid WebAuthn authentication response JSON. */
    private val fakeAuthJson = """
        {
          "id": "credential-id-auth",
          "response": {
            "clientDataJSON": "client-data-base64",
            "signature": "sig-base64"
          }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        val factory = CredentialManagerFactory { mockCredentialManager }
        service = PasskeyService(mockApiClient, tokenProvider, factory)
    }

    // ── register ─────────────────────────────────────────────────────────────

    @Test
    fun register_success_callsRegisterPasskeyWithCorrectFields() = runTest {
        val fakeRegResponse = mockk<CreatePublicKeyCredentialResponse> {
            every { registrationResponseJson } returns fakeRegistrationJson
        }
        coEvery { mockApiClient.getChallenge() } returns ApiResult.Success(fakeChallenge)
        coEvery { mockCredentialManager.createCredential(mockActivity, any()) } returns fakeRegResponse
        coEvery { mockApiClient.registerPasskey(any()) } returns ApiResult.Success(fakeToken)

        val result = service.register(mockActivity, "alice")

        assertTrue("Expected success", result.isSuccess)

        val slot = slot<PasskeyRegisterRequest>()
        coVerify { mockApiClient.registerPasskey(capture(slot)) }
        assertEquals("credential-id-reg",  slot.captured.credentialId)
        assertEquals(fakeCoseKeyBase64,    slot.captured.publicKey)
        assertEquals("client-data-base64", slot.captured.clientDataJson)
    }

    @Test
    fun register_getChallengeNetworkError_returnsFailure_credentialManagerNotCalled() = runTest {
        coEvery { mockApiClient.getChallenge() } returns ApiResult.NetworkUnavailable

        val result = service.register(mockActivity, "alice")

        assertTrue("Expected failure", result.isFailure)
        assertEquals("No network connection", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { mockCredentialManager.createCredential(any(), any()) }
        coVerify(exactly = 0) { mockApiClient.registerPasskey(any()) }
    }

    @Test
    fun register_credentialManagerThrows_returnsFailure_registerPasskeyNotCalled() = runTest {
        coEvery { mockApiClient.getChallenge() } returns ApiResult.Success(fakeChallenge)
        coEvery { mockCredentialManager.createCredential(mockActivity, any()) } throws
            RuntimeException("biometric cancelled")

        val result = service.register(mockActivity, "alice")

        assertTrue("Expected failure", result.isFailure)
        coVerify(exactly = 0) { mockApiClient.registerPasskey(any()) }
    }

    @Test
    fun register_registerPasskeyApiError_returnsFailure() = runTest {
        val fakeRegResponse = mockk<CreatePublicKeyCredentialResponse> {
            every { registrationResponseJson } returns fakeRegistrationJson
        }
        coEvery { mockApiClient.getChallenge() } returns ApiResult.Success(fakeChallenge)
        coEvery { mockCredentialManager.createCredential(mockActivity, any()) } returns fakeRegResponse
        coEvery { mockApiClient.registerPasskey(any()) } returns ApiResult.Error("Forbidden", 403)

        val result = service.register(mockActivity, "alice")

        assertTrue("Expected failure", result.isFailure)
        assertEquals("Forbidden", result.exceptionOrNull()?.message)
    }

    // ── addPasskey (#207: adding a second passkey while already signed in) ─────

    @Test
    fun addPasskey_success_callsAddPasskeyWithCorrectFields() = runTest {
        val fakeRegResponse = mockk<CreatePublicKeyCredentialResponse> {
            every { registrationResponseJson } returns fakeRegistrationJson
        }
        val fakeCredential = PasskeyCredential(
            credentialId = "credential-id-reg",
            deviceLabel = "iPad",
            createdAt = "2099-01-01T00:00:00Z"
        )
        // Already signed in: tokenProvider already holds a session before this call, and
        // addPasskey must not touch it — it authenticates via ApiClient's bearer auth, not
        // a fresh sign-in ceremony.
        tokenProvider.token = "already-signed-in-token"
        coEvery { mockApiClient.getChallenge() } returns ApiResult.Success(fakeChallenge)
        coEvery { mockCredentialManager.createCredential(mockActivity, any()) } returns fakeRegResponse
        coEvery { mockApiClient.addPasskey(any()) } returns ApiResult.Success(fakeCredential)

        val result = service.addPasskey(mockActivity, "alice")

        assertTrue("Expected success", result.isSuccess)
        assertEquals(fakeCredential, result.getOrNull())
        assertEquals("already-signed-in-token", tokenProvider.token)

        val slot = slot<AddPasskeyRequest>()
        coVerify { mockApiClient.addPasskey(capture(slot)) }
        assertEquals("credential-id-reg",  slot.captured.credentialId)
        assertEquals(fakeCoseKeyBase64,    slot.captured.publicKey)
        assertEquals("client-data-base64", slot.captured.clientDataJson)
    }

    @Test
    fun addPasskey_credentialManagerThrows_returnsFailure_addPasskeyNotCalled() = runTest {
        coEvery { mockApiClient.getChallenge() } returns ApiResult.Success(fakeChallenge)
        coEvery { mockCredentialManager.createCredential(mockActivity, any()) } throws
            RuntimeException("biometric cancelled")

        val result = service.addPasskey(mockActivity, "alice")

        assertTrue("Expected failure", result.isFailure)
        coVerify(exactly = 0) { mockApiClient.addPasskey(any()) }
    }

    @Test
    fun addPasskey_apiError_returnsFailure() = runTest {
        val fakeRegResponse = mockk<CreatePublicKeyCredentialResponse> {
            every { registrationResponseJson } returns fakeRegistrationJson
        }
        coEvery { mockApiClient.getChallenge() } returns ApiResult.Success(fakeChallenge)
        coEvery { mockCredentialManager.createCredential(mockActivity, any()) } returns fakeRegResponse
        coEvery { mockApiClient.addPasskey(any()) } returns ApiResult.Error("Forbidden", 403)

        val result = service.addPasskey(mockActivity, "alice")

        assertTrue("Expected failure", result.isFailure)
        assertEquals("Forbidden", result.exceptionOrNull()?.message)
    }

    // ── authenticate ─────────────────────────────────────────────────────────

    @Test
    fun authenticate_success_setsTokenOnTokenProvider() = runTest {
        val fakeCredential = mockk<PublicKeyCredential> {
            every { authenticationResponseJson } returns fakeAuthJson
        }
        val fakeGetResp = mockk<GetCredentialResponse> {
            every { credential } returns fakeCredential
        }
        coEvery { mockApiClient.getChallenge() } returns ApiResult.Success(fakeChallenge)
        coEvery { mockCredentialManager.getCredential(mockActivity, any<GetCredentialRequest>()) } returns fakeGetResp
        coEvery { mockApiClient.verifyPasskey(any()) } returns ApiResult.Success(fakeToken)

        val result = service.authenticate(mockActivity)

        assertTrue("Expected success", result.isSuccess)
        assertEquals("jwt.token.value", tokenProvider.token)

        val slot = slot<PasskeyVerifyRequest>()
        coVerify { mockApiClient.verifyPasskey(capture(slot)) }
        assertEquals("credential-id-auth",  slot.captured.credentialId)
        assertEquals("client-data-base64",  slot.captured.clientDataJson)
        assertEquals("sig-base64",          slot.captured.signature)
    }

    @Test
    fun authenticate_getChallengeNetworkError_returnsFailure_tokenUnchanged() = runTest {
        tokenProvider.token = "old-token"
        coEvery { mockApiClient.getChallenge() } returns ApiResult.NetworkUnavailable

        val result = service.authenticate(mockActivity)

        assertTrue("Expected failure", result.isFailure)
        assertEquals("No network connection", result.exceptionOrNull()?.message)
        assertEquals("old-token", tokenProvider.token)
        coVerify(exactly = 0) { mockCredentialManager.getCredential(any(), any<GetCredentialRequest>()) }
    }

    @Test
    fun authenticate_credentialManagerThrows_returnsFailure_tokenUnchanged() = runTest {
        tokenProvider.token = "old-token"
        coEvery { mockApiClient.getChallenge() } returns ApiResult.Success(fakeChallenge)
        coEvery { mockCredentialManager.getCredential(mockActivity, any<GetCredentialRequest>()) } throws
            RuntimeException("no credentials available")

        val result = service.authenticate(mockActivity)

        assertTrue("Expected failure", result.isFailure)
        assertEquals("old-token", tokenProvider.token)
        coVerify(exactly = 0) { mockApiClient.verifyPasskey(any()) }
    }

    @Test
    fun authenticate_verifyPasskeyApiError_returnsFailure_tokenUnchanged() = runTest {
        tokenProvider.token = "old-token"
        val fakeCredential = mockk<PublicKeyCredential> {
            every { authenticationResponseJson } returns fakeAuthJson
        }
        val fakeGetResp = mockk<GetCredentialResponse> {
            every { credential } returns fakeCredential
        }
        coEvery { mockApiClient.getChallenge() } returns ApiResult.Success(fakeChallenge)
        coEvery { mockCredentialManager.getCredential(mockActivity, any<GetCredentialRequest>()) } returns fakeGetResp
        coEvery { mockApiClient.verifyPasskey(any()) } returns ApiResult.Error("Invalid signature", 401)

        val result = service.authenticate(mockActivity)

        assertTrue("Expected failure", result.isFailure)
        assertEquals("Invalid signature", result.exceptionOrNull()?.message)
        assertEquals("old-token", tokenProvider.token)
    }

    @Test
    fun authenticate_verifyPasskeyNetworkError_returnsFailure_tokenUnchanged() = runTest {
        tokenProvider.token = "old-token"
        val fakeCredential = mockk<PublicKeyCredential> {
            every { authenticationResponseJson } returns fakeAuthJson
        }
        val fakeGetResp = mockk<GetCredentialResponse> {
            every { credential } returns fakeCredential
        }
        coEvery { mockApiClient.getChallenge() } returns ApiResult.Success(fakeChallenge)
        coEvery { mockCredentialManager.getCredential(mockActivity, any<GetCredentialRequest>()) } returns fakeGetResp
        coEvery { mockApiClient.verifyPasskey(any()) } returns ApiResult.NetworkUnavailable

        val result = service.authenticate(mockActivity)

        assertTrue("Expected failure", result.isFailure)
        assertEquals("old-token", tokenProvider.token)
    }

    // ── requireSuccess error mapping ──────────────────────────────────────────

    @Test
    fun requireSuccess_success_returnsData() {
        assertEquals("hello", service.requireSuccess(ApiResult.Success("hello")))
    }

    @Test(expected = ApiCallFailedException::class)
    fun requireSuccess_apiError_throwsWithMessage() {
        service.requireSuccess(ApiResult.Error("Something went wrong", 500))
    }

    @Test
    fun requireSuccess_apiError_exceptionHasCorrectMessage() {
        val ex = runCatching {
            service.requireSuccess(ApiResult.Error("Bad request", 400))
        }.exceptionOrNull()
        assertEquals("Bad request", ex?.message)
    }

    @Test(expected = ApiCallFailedException::class)
    fun requireSuccess_networkUnavailable_throws() {
        service.requireSuccess(ApiResult.NetworkUnavailable)
    }

    @Test
    fun requireSuccess_networkUnavailable_exceptionMessage() {
        val ex = runCatching {
            service.requireSuccess(ApiResult.NetworkUnavailable)
        }.exceptionOrNull()
        assertEquals("No network connection", ex?.message)
    }
}

package com.ethosprotocol

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.AuthChallenge
import com.ethosprotocol.services.PasskeyService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// CreatePublicKeyCredentialRequest/GetPublicKeyCredentialOption marshal their JSON payload into
// a real android.os.Bundle, which needs Robolectric's framework shadows to run on the JVM.
@RunWith(RobolectricTestRunner::class)
class PasskeyServiceTest {

    private val apiClient: ApiClient = mockk()
    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val activity: Activity = mockk(relaxed = true)
    private val credentialManager: CredentialManager = mockk()
    private lateinit var service: PasskeyService

    @Before
    fun setup() {
        mockkObject(CredentialManager.Companion)
        every { CredentialManager.create(any()) } returns credentialManager
        coEvery { apiClient.getChallenge() } returns
            ApiResult.Success(AuthChallenge("challenge", "2026-01-01T00:00:00Z"))
        service = PasskeyService(apiClient, tokenProvider)
    }

    @After
    fun teardown() {
        unmockkObject(CredentialManager.Companion)
    }

    @Test
    fun `register maps user cancellation to an actionable message`() = runTest {
        coEvery { credentialManager.createCredential(any(), any()) } throws
            CreateCredentialCancellationException("cancelled by user")

        val result = service.register(activity, "alice")

        assertTrue(result.isFailure)
        assertEquals("Sign-up canceled.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `authenticate maps no credentials available to an actionable message`() = runTest {
        coEvery { credentialManager.getCredential(any<Activity>(), any<GetCredentialRequest>()) } throws
            NoCredentialException("no credentials on this device")

        val result = service.authenticate(activity)

        assertTrue(result.isFailure)
        assertEquals(
            "No passkey found for this account on this device. Create an account or use the device you registered with.",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun `authenticate maps user cancellation to an actionable message`() = runTest {
        coEvery { credentialManager.getCredential(any<Activity>(), any<GetCredentialRequest>()) } throws
            GetCredentialCancellationException("cancelled by user")

        val result = service.authenticate(activity)

        assertTrue(result.isFailure)
        assertEquals("Sign-in canceled.", result.exceptionOrNull()?.message)
    }
}

package com.ethosprotocol

import android.content.Context
import com.ethosprotocol.services.AppIntegrityService
import com.ethosprotocol.services.AttestationToken
import com.ethosprotocol.services.IntegrityTokenProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * #274 — Unit tests for [AppIntegrityService].
 *
 * All Play Services calls are replaced by a [FakeIntegrityTokenProvider] so tests
 * run on the JVM without a real device or Google Play Services connection.
 *
 * ## Coverage
 * - Success path: token returned, provider is "playintegrity"
 * - Failure path: exception from Play Integrity surfaces as [AttestationToken.Failed]
 * - Nonce forwarded: the nonce passed to [AppIntegrityService.generateToken] is
 *   forwarded verbatim to the underlying provider
 * - Provider constant: the `X-Attestation-Provider` value matches the API contract
 */
class AppIntegrityServiceTest {

    private val context: Context = mockk(relaxed = true)
    private lateinit var fakeProvider: FakeIntegrityTokenProvider
    private lateinit var service: AppIntegrityService

    @Before
    fun setup() {
        fakeProvider = FakeIntegrityTokenProvider()
        service = AppIntegrityService(context, tokenProvider = fakeProvider)
    }

    // ── Success path ──────────────────────────────────────────────────────────

    @Test
    fun `generateToken success returns AttestationToken Success with playintegrity provider`() = runTest {
        fakeProvider.tokenToReturn = "fake.jwt.token"
        val result = service.generateToken(nonce = "test-nonce-abc123")
        assertTrue("Expected Success, got $result", result is AttestationToken.Success)
        val success = result as AttestationToken.Success
        assertEquals("fake.jwt.token", success.token)
        assertEquals(AppIntegrityService.PROVIDER_PLAY_INTEGRITY, success.provider)
    }

    @Test
    fun `generateToken success token is non-empty`() = runTest {
        fakeProvider.tokenToReturn = "eyJhbGciOiJFUzI1NiJ9.payload.signature"
        val result = service.generateToken(nonce = "nonce")
        assertTrue(result is AttestationToken.Success)
        assertFalse((result as AttestationToken.Success).token.isEmpty())
    }

    // ── Nonce forwarding ──────────────────────────────────────────────────────

    @Test
    fun `generateToken forwards nonce to the underlying provider`() = runTest {
        fakeProvider.tokenToReturn = "token"
        service.generateToken(nonce = "my-specific-nonce-12345")
        assertEquals(
            "Nonce must be forwarded verbatim to the integrity token provider",
            "my-specific-nonce-12345",
            fakeProvider.lastNonce
        )
    }

    // ── Failure path ──────────────────────────────────────────────────────────

    @Test
    fun `generateToken when provider throws returns AttestationToken Failed`() = runTest {
        fakeProvider.errorToThrow = RuntimeException("Play Services unavailable")
        val result = service.generateToken(nonce = "nonce")
        assertTrue("Expected Failed, got $result", result is AttestationToken.Failed)
        val failed = result as AttestationToken.Failed
        assertEquals("Play Services unavailable", failed.error.message)
    }

    @Test
    fun `generateToken when provider throws does not rethrow`() = runTest {
        fakeProvider.errorToThrow = IllegalStateException("Simulated crash")
        // Must not throw — the service wraps exceptions in AttestationToken.Failed.
        val result = service.generateToken(nonce = "nonce")
        assertFalse("Result must not be Success when provider throws", result is AttestationToken.Success)
    }

    // ── Provider constant ─────────────────────────────────────────────────────

    @Test
    fun `PROVIDER_PLAY_INTEGRITY constant matches API contract`() {
        // The backend checks this string — it must match exactly.
        assertEquals("playintegrity", AppIntegrityService.PROVIDER_PLAY_INTEGRITY)
    }

    // ── Distinct nonces produce distinct results ───────────────────────────────

    @Test
    fun `generateToken called with different nonces forwards each nonce`() = runTest {
        fakeProvider.tokenToReturn = "token-a"
        service.generateToken(nonce = "nonce-a")
        assertEquals("nonce-a", fakeProvider.lastNonce)

        fakeProvider.tokenToReturn = "token-b"
        service.generateToken(nonce = "nonce-b")
        assertEquals("nonce-b", fakeProvider.lastNonce)
    }
}

// ── Test double ───────────────────────────────────────────────────────────────

/** Controllable stub for [IntegrityTokenProvider]. */
private class FakeIntegrityTokenProvider : IntegrityTokenProvider {
    var tokenToReturn: String = "stub-token"
    var errorToThrow: Throwable? = null
    var lastNonce: String? = null

    override suspend fun requestToken(nonce: String): String {
        lastNonce = nonce
        errorToThrow?.let { throw it }
        return tokenToReturn
    }
}

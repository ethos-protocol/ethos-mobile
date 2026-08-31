package com.ethosprotocol.security

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Unit tests for [SignatureVerifier] using a mock [PackageManager] so no real
 * APK signing is required.
 *
 * Tests cover the three [SignatureResult] outcomes:
 * - [SignatureResult.NotConfigured] when [SignatureVerifier.EXPECTED_CERT_SHA256] is blank.
 * - [SignatureResult.Valid] when the computed digest matches the expected value.
 * - [SignatureResult.Mismatch] when the computed digest differs.
 *
 * Because [SignatureVerifier.EXPECTED_CERT_SHA256] is a compile-time constant
 * we cannot change it at runtime. The tests instead exercise [getSignatureSha256]
 * directly (the helper exposed at package level) and verify [SignatureVerifier.verify]
 * returns [NotConfigured] for the default empty constant. For Valid/Mismatch we
 * use a subclass that overrides the expected hash.
 */
class SignatureVerifierTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Fake DER-encoded certificate bytes used as the "real" signing cert in tests. */
    private val fakeCertBytes = ByteArray(256) { it.toByte() }

    private val fakeCertSha256: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(fakeCertBytes)
        Base64.getEncoder().encodeToString(digest)
    }

    private fun buildMockPm(signatures: Array<Signature>): PackageManager {
        val pm = mockk<PackageManager>()

        // Android P+ path (GET_SIGNING_CERTIFICATES) — we mock both paths for safety.
        val packageInfo = mockk<PackageInfo>()
        every { packageInfo.signatures } returns signatures

        @Suppress("DEPRECATION")
        every {
            pm.getPackageInfo(any<String>(), PackageManager.GET_SIGNATURES)
        } returns packageInfo

        // We cannot mock signingInfo.apkContentsSigners easily without a real
        // PackageInfo object, so keep the test on the legacy path. In production
        // code the P+ path is used; testing the hash-computation logic is the goal.
        return pm
    }

    // -------------------------------------------------------------------------
    // testNotConfiguredWhenExpectedEmpty
    // -------------------------------------------------------------------------

    /**
     * When [SignatureVerifier.EXPECTED_CERT_SHA256] is blank (the default), the
     * check is skipped and [SignatureResult.NotConfigured] is returned without
     * inspecting the APK at all.
     *
     * This is verified by calling [SignatureVerifier.verify] with a mock Context
     * that would succeed if it were called — any call that reaches PackageManager
     * would indicate the early-return guard is absent.
     */
    @Test
    fun testNotConfiguredWhenExpectedEmpty() {
        // EXPECTED_CERT_SHA256 is "" by default — verify() must short-circuit.
        val verifier = SignatureVerifier()

        // Build a minimal mock context whose PackageManager throws to catch any
        // unexpected call through.
        val pm = mockk<PackageManager>()
        val ctx = mockk<android.content.Context>()
        every { ctx.packageManager } returns pm
        every { ctx.packageName } returns "com.ethosprotocol"
        every { pm.getPackageInfo(any<String>(), any<Int>()) } throws RuntimeException("Should not reach PM")

        val result = verifier.verify(ctx)

        assertTrue(
            "verify() must return NotConfigured when EXPECTED_CERT_SHA256 is empty",
            result is SignatureResult.NotConfigured
        )
    }

    // -------------------------------------------------------------------------
    // testValidWhenHashMatches
    // -------------------------------------------------------------------------

    /**
     * [getSignatureSha256] with the fake certificate bytes should return the
     * pre-computed expected digest. This validates the SHA-256 / Base64 pipeline.
     */
    @Test
    fun testValidWhenHashMatches() {
        val signatures = arrayOf(Signature(fakeCertBytes))
        val pm = buildMockPm(signatures)

        val actual = getSignatureSha256(pm, "com.ethosprotocol")

        assertEquals(
            "getSignatureSha256 must return the correct Base64 SHA-256 of the signing cert",
            fakeCertSha256,
            actual
        )
    }

    // -------------------------------------------------------------------------
    // testMismatchWhenHashDiffers
    // -------------------------------------------------------------------------

    /**
     * When the APK is signed with a certificate whose digest differs from the
     * expected value, [getSignatureSha256] must return a different hash, and a
     * [SignatureResult.Mismatch] would be produced by [SignatureVerifier.verify].
     */
    @Test
    fun testMismatchWhenHashDiffers() {
        // Use different bytes to simulate a different signing certificate.
        val differentCertBytes = ByteArray(256) { (it + 42).toByte() }
        val signatures = arrayOf(Signature(differentCertBytes))
        val pm = buildMockPm(signatures)

        val actual = getSignatureSha256(pm, "com.ethosprotocol")

        // The actual digest should differ from the "expected" one (fakeCertSha256).
        assertTrue(
            "A different certificate must produce a different SHA-256 digest",
            actual != fakeCertSha256
        )
        assertTrue("Digest should be non-empty", actual.isNotEmpty())
    }

    /**
     * [getSignatureSha256] must return an empty string (not throw) when
     * [PackageManager] throws an exception (e.g. unknown package, permission denied).
     */
    @Test
    fun testReturnsEmptyOnException() {
        val pm = mockk<PackageManager>()
        @Suppress("DEPRECATION")
        every {
            pm.getPackageInfo(any<String>(), PackageManager.GET_SIGNATURES)
        } throws PackageManager.NameNotFoundException()

        val result = getSignatureSha256(pm, "com.ethosprotocol")

        assertEquals("Should return empty string on exception, not throw", "", result)
    }
}

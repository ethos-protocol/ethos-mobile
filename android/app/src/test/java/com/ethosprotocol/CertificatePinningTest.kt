package com.ethosprotocol

import com.ethosprotocol.api.CertificatePinner
import com.ethosprotocol.api.PinningTrustManager
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/**
 * #117 — Tests for [CertificatePinner] and [PinningTrustManager].
 *
 * The [CertificatePinner.spkiSHA256] method is overridden in a subclass so the
 * tests inject a fixed hash without needing to reverse SHA-256 or create real
 * certificate DER blobs.
 *
 * Tests cover:
 * - Matching SPKI hash → connection accepted.
 * - Mismatching SPKI hash → [SSLPeerUnverifiedException] thrown.
 * - Pin-rotation strategy: two-pin setup accepts either pin, rejects unknown certs.
 * - Empty pin set disables pinning (lets system trust store decide).
 */
class CertificatePinningTest {

    // ── isPinningEnabled ──────────────────────────────────────────────────

    @Test
    fun `isPinningEnabled true when pins are present`() {
        assertTrue(pinner("realPin==").isPinningEnabled)
    }

    @Test
    fun `isPinningEnabled false when pin set is empty`() {
        assertFalse(pinner().isPinningEnabled)
    }

    // ── checkPin ─────────────────────────────────────────────────────────

    @Test
    fun `checkPin matching hash returns true`() {
        val p = pinner("abc123==", mockHash = "abc123==")
        assertTrue(p.checkPin(fakeCertChain()))
    }

    @Test
    fun `checkPin mismatching hash returns false`() {
        val p = pinner("correctPin==", mockHash = "wrongPin==")
        assertFalse(p.checkPin(fakeCertChain()))
    }

    @Test
    fun `checkPin empty pinSet always returns true (pinning disabled)`() {
        // No pins → fall through to system trust; checkPin must not reject
        val p = pinner(mockHash = "anything==")   // no pins
        assertTrue(p.checkPin(fakeCertChain()))
    }

    // ── Rotation strategy ─────────────────────────────────────────────────

    @Test
    fun `rotation two-pin setup accepts current certificate`() {
        val p = pinner("currentPin==", "backupPin==", mockHash = "currentPin==")
        assertTrue("Current cert must be accepted", p.checkPin(fakeCertChain()))
    }

    @Test
    fun `rotation two-pin setup accepts backup certificate after rotation`() {
        val p = pinner("currentPin==", "backupPin==", mockHash = "backupPin==")
        assertTrue(
            "Backup cert must be accepted — this is how rotation avoids a hard outage",
            p.checkPin(fakeCertChain())
        )
    }

    @Test
    fun `rotation two-pin setup rejects unknown certificate`() {
        val p = pinner("currentPin==", "backupPin==", mockHash = "mitmPin==")
        assertFalse(
            "MITM cert must be rejected even when two legitimate pins are present",
            p.checkPin(fakeCertChain())
        )
    }

    // ── PinningTrustManager ───────────────────────────────────────────────

    @Test
    fun `checkServerTrusted matching pin passes without exception`() {
        val p = pinner("validPin==", mockHash = "validPin==")
        val tm = PinningTrustManager(p, mockk(relaxed = true))
        tm.checkServerTrusted(fakeCertChain(), "RSA")   // must not throw
    }

    @Test(expected = SSLPeerUnverifiedException::class)
    fun `checkServerTrusted mismatching pin throws SSLPeerUnverifiedException`() {
        val p = pinner("correctPin==", mockHash = "wrongPin==")
        val tm = PinningTrustManager(p, mockk(relaxed = true))
        tm.checkServerTrusted(fakeCertChain(), "RSA")   // must throw
    }

    @Test
    fun `checkServerTrusted empty pinSet does not throw (pinning disabled)`() {
        val p = pinner(mockHash = "any==")   // no pins
        val tm = PinningTrustManager(p, mockk(relaxed = true))
        tm.checkServerTrusted(fakeCertChain(), "RSA")   // must not throw
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Creates a [CertificatePinner] whose [spkiSHA256] is overridden to return
     * [mockHash] for every certificate. This removes the need to create real DER
     * certificate bytes or reverse SHA-256.
     *
     * @param pins  Zero or more pin values to add to [CertificatePinner.pinnedHashes].
     * @param mockHash  The hash that the overridden [spkiSHA256] will return.
     */
    private fun pinner(vararg pins: String, mockHash: String = ""): CertificatePinner {
        return object : CertificatePinner(
            pinnedHashes = pins.toSet()
        ) {
            override fun spkiSHA256(cert: X509Certificate): String = mockHash
        }
    }

    /** Returns a one-element chain of a relaxed [X509Certificate] mock. */
    private fun fakeCertChain(): Array<X509Certificate> =
        arrayOf(mockk(relaxed = true))
}

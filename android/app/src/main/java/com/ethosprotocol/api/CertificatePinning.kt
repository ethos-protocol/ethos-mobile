package com.ethosprotocol.api

import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #117 — Public-key pinning for `api.ethos-protocol.app`.
 *
 * Computes the SHA-256 digest of each certificate's SubjectPublicKeyInfo (SPKI)
 * bytes and compares it against the configured pin set.
 *
 * ## Pin rotation strategy
 *
 * Always configure at least two pins:
 *   - **Current**: SHA-256 of the live certificate's SPKI.
 *   - **Backup**: SHA-256 of the next certificate that will replace it.
 *
 * Rotation procedure:
 *   1. Generate the new certificate and compute `sha256(SPKI)`.
 *   2. Add the new hash to [pinnedHashes] and ship the app update.
 *   3. Once the old certificate is replaced on the server, remove its hash in
 *      the next release.
 *
 * This rolling two-pin approach avoids a hard outage when the certificate is
 * renewed, while still rejecting MITM certificates.
 *
 * ## Mismatch rejection
 *
 * When no certificate in the chain matches a pinned hash, [checkPin] returns
 * `false` and the caller (typically the Ktor engine's connection factory) must
 * close the connection and surface an error to the caller.
 */
open class CertificatePinner(
    /** Hostname that pinning is enforced for. */
    val pinnedHost: String = PINNED_HOST,
    /**
     * Set of Base64-encoded SHA-256 SPKI hashes.
     * Empty set disables pinning (e.g. local-dev builds pointing at a different host).
     */
    val pinnedHashes: Set<String> = DEFAULT_PINS
) {

    val isPinningEnabled: Boolean get() = pinnedHashes.isNotEmpty()

    /**
     * Returns `true` iff at least one certificate in [chain] has an SPKI hash
     * that matches a value in [pinnedHashes].
     *
     * Returns `true` unconditionally when [isPinningEnabled] is `false` (no
     * pins configured) so the caller falls back to the system trust store.
     */
    fun checkPin(chain: Array<out X509Certificate>): Boolean {
        if (!isPinningEnabled) return true
        return chain.any { cert ->
            val hash = spkiSHA256(cert)
            pinnedHashes.contains(hash)
        }
    }

    /**
     * Returns the Base64-encoded SHA-256 digest of [cert]'s
     * SubjectPublicKeyInfo DER bytes.
     */
    open fun spkiSHA256(cert: X509Certificate): String {
        val spki = cert.publicKey.encoded   // Returns SPKI bytes per Java spec
        val digest = MessageDigest.getInstance("SHA-256").digest(spki)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    companion object {
        const val PINNED_HOST = "api.ethos-protocol.app"

        /**
         * Placeholder pins — replace with real SPKI SHA-256 hashes before
         * shipping to production.
         *
         * To compute a pin from a live certificate:
         *   openssl s_client -connect api.ethos-protocol.app:443 2>/dev/null \
         *     | openssl x509 -pubkey -noout \
         *     | openssl pkey -pubin -outform der \
         *     | openssl dgst -sha256 -binary \
         *     | openssl enc -base64
         *
         * Two entries are required: the current certificate pin and a backup
         * pin for the next certificate (rotation strategy — see class docs).
         */
        val DEFAULT_PINS: Set<String> = setOf(
            // Current certificate SPKI SHA-256 (replace with real value)
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            // Backup certificate SPKI SHA-256 (replace with real value)
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
        )
    }
}

/**
 * A [X509TrustManager] that wraps a [CertificatePinner].
 *
 * It first performs standard certificate-chain validation (via the system trust
 * manager), then applies SPKI-pin checking. If either step fails the handshake
 * is rejected.
 */
class PinningTrustManager(
    private val pinner: CertificatePinner,
    private val delegate: X509TrustManager
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        // Standard chain validation first
        delegate.checkServerTrusted(chain, authType)

        // Then pin checking for the pinned host
        if (!pinner.checkPin(chain)) {
            throw javax.net.ssl.SSLPeerUnverifiedException(
                "Certificate pin mismatch for ${pinner.pinnedHost} — connection rejected. " +
                "None of the ${chain.size} certificate(s) in the chain matched any pinned hash. " +
                "If the server certificate was recently renewed, update the pins in " +
                "CertificatePinner.DEFAULT_PINS."
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

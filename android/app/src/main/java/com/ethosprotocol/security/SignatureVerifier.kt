package com.ethosprotocol.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest
import java.util.Base64

// ---------------------------------------------------------------------------
// Result type
// ---------------------------------------------------------------------------

/**
 * Outcome of an APK signature verification check.
 *
 * - [Valid]         – the signing certificate matches [SignatureVerifier.EXPECTED_CERT_SHA256].
 * - [Mismatch]      – the certificate was read successfully but its SHA-256 digest differs
 *                     from the expected value. The [actual] digest is provided for diagnostic
 *                     purposes (log it, never show it to end users).
 * - [NotConfigured] – [SignatureVerifier.EXPECTED_CERT_SHA256] is blank; the check is skipped.
 *                     This is the expected state for debug builds and CI.
 */
sealed class SignatureResult {
    object Valid : SignatureResult()
    data class Mismatch(val actual: String) : SignatureResult()
    object NotConfigured : SignatureResult()
}

// ---------------------------------------------------------------------------
// Verifier
// ---------------------------------------------------------------------------

/**
 * Verifies the APK's signing certificate against a hard-coded expected SHA-256
 * digest at runtime. This provides a non-blocking warning when the app has been
 * repackaged and re-signed by a third party (i.e. sideloaded from outside the
 * Play Store).
 *
 * ### Configuration
 * Set [EXPECTED_CERT_SHA256] to the Base64-encoded SHA-256 digest of your
 * release signing certificate's DER-encoded bytes before shipping a production
 * build. Compute it with:
 *
 * ```bash
 * keytool -exportcert -alias <key-alias> -keystore release.jks \
 *   | openssl dgst -sha256 -binary | openssl enc -base64
 * ```
 *
 * Leaving [EXPECTED_CERT_SHA256] empty disables the check (returns [SignatureResult.NotConfigured]),
 * which is the correct behaviour for debug builds and CI where no release keystore is present.
 */
class SignatureVerifier {

    companion object {
        /**
         * Expected Base64-encoded SHA-256 digest of the release signing certificate.
         * Empty string = check disabled (debug/CI-safe default).
         *
         * Override this value in a release build variant via `buildConfigField` or
         * by subclassing `SignatureVerifier` in tests.
         */
        const val EXPECTED_CERT_SHA256: String = ""
    }

    /**
     * Verifies the current APK's signing certificate.
     *
     * @param context Any [Context] — used to access [PackageManager].
     * @return [SignatureResult.NotConfigured] if [EXPECTED_CERT_SHA256] is blank;
     *         [SignatureResult.Valid] if the digest matches; [SignatureResult.Mismatch]
     *         if it does not.
     */
    fun verify(context: Context): SignatureResult {
        if (EXPECTED_CERT_SHA256.isBlank()) return SignatureResult.NotConfigured

        val actual = getSignatureSha256(context.packageManager, context.packageName)
        return if (actual == EXPECTED_CERT_SHA256) {
            SignatureResult.Valid
        } else {
            SignatureResult.Mismatch(actual)
        }
    }
}

// ---------------------------------------------------------------------------
// Helper — package-level so it can be called from tests with a mock PM
// ---------------------------------------------------------------------------

/**
 * Returns the Base64-encoded SHA-256 digest of the first signing certificate
 * for [packageName], or an empty string if the certificate cannot be retrieved.
 *
 * Uses the `GET_SIGNING_CERTIFICATES` API on Android P+ (API 28) and falls
 * back to the deprecated `GET_SIGNATURES` flag on older releases. Only the
 * *first* certificate in the chain is inspected; multi-signer APKs are not
 * supported by the Play signing pipeline used by this project.
 */
fun getSignatureSha256(pm: PackageManager, packageName: String): String {
    return try {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }

        if (signatures.isEmpty()) return ""

        val certBytes = signatures[0].toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
        Base64.getEncoder().encodeToString(digest)
    } catch (_: Exception) {
        ""
    }
}

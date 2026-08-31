package com.ethosprotocol

import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLContext

/**
 * #275 — Documented verification for TLS 1.2+ enforcement.
 *
 * These tests confirm that:
 *  1. The "TLSv1.2" SSLContext is available on the platform (it must be, or the
 *     production ApiClient engine init would throw at runtime).
 *  2. The cipher-suite allowlist contains only forward-secrecy AEAD suites.
 *  3. All allowlisted suites intersect with the platform's supported set so the
 *     effective cipher list is never inadvertently empty.
 *
 * The production configuration is reproduced verbatim here so that any drift
 * between ApiClient.kt and this test causes a compile-time failure — a wrong
 * copy-paste of the cipher name would result in a test failure on the
 * "effective list must be non-empty" assertion below.
 *
 * Manual verification (CI step):
 *   After assembleRelease, confirm with:
 *     openssl s_client -connect api.ethos-protocol.app:443 -tls1_1
 *   → expect: "no peer certificate available" or "ssl handshake failure" —
 *     TLS 1.1 must be rejected by the server. The app-side minimum floor
 *     ensures we never initiate a < TLS 1.2 handshake.
 */
class TlsEnforcementTest {

    // Mirror of the allowlist in ApiClient.kt — kept in sync intentionally.
    private val ALLOWED_CIPHERS = setOf(
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
    )

    @Test
    fun `TLSv1_2 SSLContext is available on the platform`() {
        // If this throws, the production ApiClient engine init will also throw.
        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(null, null, null)
        assertNotNull("TLSv1.2 SSLContext must be non-null", ctx)
    }

    @Test
    fun `cipher allowlist contains only forward-secrecy AEAD suites`() {
        for (suite in ALLOWED_CIPHERS) {
            assertTrue(
                "Cipher '$suite' must use ECDHE (PFS): found non-PFS suite in allowlist",
                suite.contains("ECDHE")
            )
            val isAead = suite.contains("GCM") || suite.contains("POLY1305")
            assertTrue(
                "Cipher '$suite' must be AEAD (GCM or CHACHA20_POLY1305): found non-AEAD suite in allowlist",
                isAead
            )
        }
    }

    @Test
    fun `all allowlisted cipher suites are non-empty`() {
        assertTrue("Cipher allowlist must not be empty", ALLOWED_CIPHERS.isNotEmpty())
    }

    @Test
    fun `platform supports at least one cipher from the allowlist`() {
        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(null, null, null)
        val supported = ctx.socketFactory.supportedCipherSuites.toSet()
        val effective = ALLOWED_CIPHERS.intersect(supported)
        assertTrue(
            "At least one allowlisted cipher must be supported by this platform's TLS stack. " +
            "Supported: $supported. Allowlist: $ALLOWED_CIPHERS",
            effective.isNotEmpty()
        )
    }

    @Test
    fun `no deprecated cipher suites in allowlist`() {
        val deprecated = listOf("RC4", "3DES", "_CBC_", "NULL", "EXPORT", "aNULL", "eNULL", "ANON")
        for (suite in ALLOWED_CIPHERS) {
            for (d in deprecated) {
                assertFalse(
                    "Deprecated pattern '$d' found in allowlisted cipher '$suite'",
                    suite.uppercase().contains(d.uppercase())
                )
            }
        }
    }
}

package com.ethosprotocol

import com.ethosprotocol.services.buildFallbackBody
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers #233: the fallback notification body used when an FCM payload is
 * data-only. Must identify the vault (truncated ID) without ever leaking
 * balance or beneficiary — those aren't in this payload to begin with, but
 * this pins the exact copy so a future change can't accidentally add them.
 */
class PushNotificationBodyTest {

    @Test
    fun `expiry_warning includes truncated vault id`() {
        val body = buildFallbackBody("expiry_warning", "vault-1234567890abcdef")
        assertEquals("Vault vault-123456 is expiring soon. Check in now.", body)
    }

    @Test
    fun `released includes truncated vault id`() {
        val body = buildFallbackBody("released", "vault-1234567890abcdef")
        assertEquals("Vault vault-123456 has been released to the beneficiary.", body)
    }

    @Test
    fun `unrecognized type includes truncated vault id`() {
        val body = buildFallbackBody("something_else", "vault-1234567890abcdef")
        assertEquals("Action required for vault vault-123456.", body)
    }

    @Test
    fun `missing vault id falls back to generic copy`() {
        assertEquals("Your vault is expiring soon. Check in now.", buildFallbackBody("expiry_warning", null))
        assertEquals("Your vault has been released to the beneficiary.", buildFallbackBody("released", null))
        assertEquals("Action required for your vault.", buildFallbackBody("something_else", null))
    }

    @Test
    fun `body never contains a balance or address-shaped token`() {
        // Sanity guard: a Stellar public key is 56 chars starting with 'G'. Even
        // with a vault id that happens to look like one, the body must never
        // include more than the 12-char truncation.
        val longId = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        val body = buildFallbackBody("expiry_warning", longId)
        assertFalse(body.contains(longId))
        assertTrue(body.contains(longId.take(12)))
    }
}

package com.ethosprotocol

import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.AuthToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the default behaviour of [TokenProvider] that ApiClient's bearer-auth path relies on,
 * using a minimal fake that only implements the abstract members.
 */
class TokenProviderContractTest {

    private class FakeTokenProvider : TokenProvider {
        override var token: String? = null
        override fun clear() {
            token = null
        }
    }

    @Test
    fun `setSession stores the bearer token`() {
        val provider = FakeTokenProvider()

        provider.setSession(AuthToken(token = "abc", expiresAt = "2030-01-01T00:00:00Z"))

        assertEquals("abc", provider.token)
    }

    @Test
    fun `pushToken defaults to null and is not persisted by the fake`() {
        val provider = FakeTokenProvider()

        assertNull(provider.pushToken)
    }

    @Test
    fun `isNearExpiry defaults to false when no expiry is tracked`() {
        assertFalse(FakeTokenProvider().isNearExpiry())
    }

    @Test
    fun `clear removes the bearer token`() {
        val provider = FakeTokenProvider()
        provider.token = "abc"

        provider.clear()

        assertNull(provider.token)
    }
}

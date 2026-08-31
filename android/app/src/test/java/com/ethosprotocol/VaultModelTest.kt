package com.ethosprotocol

import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import org.junit.Assert.*
import org.junit.Test

class VaultModelTest {

    @Test
    fun `isExpiringSoon true when ttl under 24h`() {
        val vault = makeVault(ttlRemaining = 3_600L)
        assertTrue(vault.isExpiringSoon)
    }

    @Test
    fun `isExpiringSoon false when ttl over 24h`() {
        val vault = makeVault(ttlRemaining = 172_800L)
        assertFalse(vault.isExpiringSoon)
    }

    @Test
    fun `isExpiringSoon false when ttl null`() {
        val vault = makeVault(ttlRemaining = null)
        assertFalse(vault.isExpiringSoon)
    }

    @Test
    fun `formattedBalance converts stroops to XLM`() {
        val vault = makeVault(balance = 10_000_000L)
        assertEquals("1.0000000 XLM", vault.formattedBalance)
    }

    @Test
    fun `formattedBalance handles zero`() {
        val vault = makeVault(balance = 0L)
        assertEquals("0.0000000 XLM", vault.formattedBalance)
    }

    // #218: display name prefers the label, falls back to a truncated ID.
    @Test
    fun `displayName with label returns label`() {
        val vault = makeVault(id = "vault-1234567890abcdef", label = "Emergency Fund")
        assertEquals("Emergency Fund", vault.displayName)
    }

    @Test
    fun `displayName without label returns truncated id`() {
        val vault = makeVault(id = "vault-1234567890abcdef")
        assertEquals("vault-123456", vault.displayName)
    }

    private fun makeVault(
        id: String = "v1",
        balance: Long = 0L,
        ttlRemaining: Long? = null,
        label: String? = null
    ) = Vault(
        id = id, owner = "GABC", beneficiary = "GXYZ",
        balance = balance, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = ttlRemaining,
        status = VaultStatus.active, label = label
    )
}

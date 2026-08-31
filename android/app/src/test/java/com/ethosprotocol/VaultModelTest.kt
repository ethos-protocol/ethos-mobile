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

    // #222: assetCode defaults to "XLM" when the server omits it, but formats
    // whatever code is actually present — preparation for a non-XLM vault.
    @Test
    fun `formattedBalance uses assetCode default of XLM when unset`() {
        val vault = makeVault(balance = 10_000_000L)
        assertEquals("XLM", vault.assetCode)
        assertNull(vault.assetIssuer)
    }

    @Test
    fun `formattedBalance formats a non-XLM asset amount correctly`() {
        val vault = makeVault(
            balance = 500_000_000L,
            assetCode = "USDC",
            assetIssuer = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        )
        assertEquals("50.0000000 USDC", vault.formattedBalance)
    }

    private fun makeVault(
        balance: Long = 0L,
        ttlRemaining: Long? = null,
        assetCode: String = "XLM",
        assetIssuer: String? = null
    ) = Vault(
        id = "v1", owner = "GABC", beneficiary = "GXYZ",
        balance = balance, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = ttlRemaining,
        status = VaultStatus.active, assetCode = assetCode, assetIssuer = assetIssuer
    )
}

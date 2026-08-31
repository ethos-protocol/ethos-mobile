package com.ethosprotocol

import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultListFilter
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.models.filterVaults
import org.junit.Assert.*
import org.junit.Test

/** Covers #219: client-side vault search/status filter. */
class VaultListFilterTest {

    private fun makeVault(
        id: String,
        status: VaultStatus = VaultStatus.active,
        label: String? = null,
        ttlRemaining: Long? = 172_800L
    ) = Vault(
        id = id, owner = "GABC", beneficiary = "GXYZ", balance = 0L,
        checkInInterval = 2_592_000L, lastCheckIn = "2026-04-01T00:00:00Z",
        ttlRemaining = ttlRemaining, status = status, label = label
    )

    @Test
    fun `blank search all status returns everything`() {
        val vaults = listOf(makeVault("vault-a"), makeVault("vault-b"))
        val result = filterVaults(vaults, "", VaultListFilter.ALL)
        assertEquals(2, result.size)
    }

    @Test
    fun `search by label matches case insensitively`() {
        val vaults = listOf(
            makeVault("vault-a", label = "Emergency Fund"),
            makeVault("vault-b", label = "College Fund")
        )
        val result = filterVaults(vaults, "emergency", VaultListFilter.ALL)
        assertEquals(listOf("vault-a"), result.map { it.id })
    }

    @Test
    fun `search by id matches when no label`() {
        val vaults = listOf(makeVault("vault-abc123"), makeVault("vault-xyz789"))
        val result = filterVaults(vaults, "abc123", VaultListFilter.ALL)
        assertEquals(listOf("vault-abc123"), result.map { it.id })
    }

    @Test
    fun `search with whitespace is trimmed`() {
        val vaults = listOf(makeVault("vault-a", label = "Fund"))
        val result = filterVaults(vaults, "  fund  ", VaultListFilter.ALL)
        assertEquals(1, result.size)
    }

    @Test
    fun `status active excludes other statuses`() {
        val vaults = listOf(
            makeVault("a", status = VaultStatus.active),
            makeVault("b", status = VaultStatus.expired),
            makeVault("c", status = VaultStatus.released)
        )
        val result = filterVaults(vaults, "", VaultListFilter.ACTIVE)
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `status expired excludes active`() {
        val vaults = listOf(makeVault("a", status = VaultStatus.active), makeVault("b", status = VaultStatus.expired))
        val result = filterVaults(vaults, "", VaultListFilter.EXPIRED)
        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun `status expiring soon requires active and under 24h`() {
        val vaults = listOf(
            makeVault("soon", status = VaultStatus.active, ttlRemaining = 3_600L),
            makeVault("later", status = VaultStatus.active, ttlRemaining = 172_800L),
            makeVault("expired-and-soon-ttl", status = VaultStatus.expired, ttlRemaining = 3_600L)
        )
        val result = filterVaults(vaults, "", VaultListFilter.EXPIRING_SOON)
        assertEquals(listOf("soon"), result.map { it.id })
    }

    @Test
    fun `combines search and status`() {
        val vaults = listOf(
            makeVault("vault-a", status = VaultStatus.active, label = "Fund"),
            makeVault("vault-b", status = VaultStatus.expired, label = "Fund")
        )
        val result = filterVaults(vaults, "fund", VaultListFilter.ACTIVE)
        assertEquals(listOf("vault-a"), result.map { it.id })
    }

    @Test
    fun `works across already fetched paginated results`() {
        // Simulates vaults accumulated from multiple pages — the filter is
        // purely local, so page boundaries are irrelevant to it.
        val page1 = listOf(makeVault("p1-a"), makeVault("p1-b", label = "Target"))
        val page2 = listOf(makeVault("p2-a"), makeVault("p2-b"))
        val result = filterVaults(page1 + page2, "target", VaultListFilter.ALL)
        assertEquals(listOf("p1-b"), result.map { it.id })
    }
}

package com.ethosprotocol

import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import org.junit.Assert.*
import org.junit.Test

class VaultWidgetUpdateWorkerTest {

    @Test
    fun `selects the active vault with the lowest ttlRemaining, not the first one returned`() {
        val vaults = listOf(
            makeVault("v1", ttl = 500_000, status = VaultStatus.active),
            makeVault("v2", ttl = 1_000, status = VaultStatus.active),
            makeVault("v3", ttl = 200_000, status = VaultStatus.active),
        )

        val selected = VaultWidgetUpdateWorker.selectUrgentVault(vaults)

        assertEquals("v2", selected?.id)
    }

    @Test
    fun `excludes non-active vaults even when their ttl is lower`() {
        val vaults = listOf(
            makeVault("v1", ttl = 500_000, status = VaultStatus.active),
            makeVault("v2", ttl = 1_000, status = VaultStatus.expired),
            makeVault("v3", ttl = 500, status = VaultStatus.paused),
        )

        val selected = VaultWidgetUpdateWorker.selectUrgentVault(vaults)

        assertEquals("v1", selected?.id)
    }

    @Test
    fun `returns null when there are no active vaults`() {
        val vaults = listOf(
            makeVault("v1", ttl = 500_000, status = VaultStatus.paused),
            makeVault("v2", ttl = 1_000, status = VaultStatus.released),
        )

        assertNull(VaultWidgetUpdateWorker.selectUrgentVault(vaults))
    }

    @Test
    fun `returns null for an empty vault list`() {
        assertNull(VaultWidgetUpdateWorker.selectUrgentVault(emptyList()))
    }

    @Test
    fun `treats a null ttlRemaining as least urgent`() {
        val vaults = listOf(
            makeVault("unknown-ttl", ttl = null, status = VaultStatus.active),
            makeVault("known-ttl", ttl = 100, status = VaultStatus.active),
        )

        val selected = VaultWidgetUpdateWorker.selectUrgentVault(vaults)

        assertEquals("known-ttl", selected?.id)
    }

    // Regression guard for parity with iOS's TTLTimelineProvider
    // (.filter { status == .active }.min(by: ttlRemaining)) — must not silently
    // regress back to `result.data.firstOrNull()`.
    @Test
    fun `never falls back to the first vault when a later one is more urgent`() {
        val vaults = listOf(
            makeVault("first", ttl = 999_999, status = VaultStatus.active),
            makeVault("most-urgent", ttl = 1, status = VaultStatus.active),
        )

        val selected = VaultWidgetUpdateWorker.selectUrgentVault(vaults)

        assertNotEquals("first", selected?.id)
        assertEquals("most-urgent", selected?.id)
    }

    private fun makeVault(id: String, ttl: Long?, status: VaultStatus) = Vault(
        id = id, owner = "GABC", beneficiary = "GXYZ",
        balance = 10_000_000L, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = ttl,
        status = status
    )
}

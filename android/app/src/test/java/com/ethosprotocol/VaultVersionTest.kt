package com.ethosprotocol

import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers #716: the restored Vault declaration keeps the version-based reconciliation rule. */
class VaultVersionTest {

    private fun vault(version: Long = Vault.DEFAULT_VERSION) = Vault(
        id = "vault-1", owner = "GABC", beneficiary = "GXYZ", balance = 0L,
        checkInInterval = 86_400L, lastCheckIn = "2026-01-01T00:00:00Z",
        status = VaultStatus.active, version = version
    )

    @Test
    fun `version defaults to DEFAULT_VERSION when omitted`() {
        assertEquals(Vault.DEFAULT_VERSION, vault().version)
    }

    @Test
    fun `higher version is newer`() {
        assertTrue(vault(version = 2L).isNewerThan(vault(version = 1L)))
    }

    @Test
    fun `lower version is not newer`() {
        assertFalse(vault(version = 1L).isNewerThan(vault(version = 2L)))
    }

    @Test
    fun `equal versions are not newer so applied state is kept`() {
        assertFalse(vault(version = 3L).isNewerThan(vault(version = 3L)))
    }

    @Test
    fun `any snapshot is newer than no snapshot`() {
        assertTrue(vault().isNewerThan(null))
    }

    @Test
    fun `unversioned snapshot never overwrites a versioned one`() {
        assertFalse(vault().isNewerThan(vault(version = 1L)))
    }
}

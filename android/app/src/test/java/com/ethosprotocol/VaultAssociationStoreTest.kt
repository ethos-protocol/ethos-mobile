package com.ethosprotocol

import android.content.Context
import android.content.SharedPreferences
import com.ethosprotocol.services.VaultAssociationStore
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * #200: cross-device backup/restore for vault-to-passkey-credential associations, backed
 * by a SharedPreferences file included in Android's Auto Backup for Apps
 * (res/xml/data_extraction_rules.xml, res/xml/backup_rules.xml).
 */
class VaultAssociationStoreTest {

    private lateinit var backing: MutableMap<String, String>
    private lateinit var store: VaultAssociationStore

    @Before
    fun setup() {
        backing = mutableMapOf()
        val context: Context = mockk()
        every { context.getSharedPreferences(any<String>(), any()) } returns fakeSharedPreferences()
        store = VaultAssociationStore(context)
    }

    @Test
    fun `save then credentialId returns the saved credential`() {
        store.save("vault-1", "cred-abc")

        assertEquals("cred-abc", store.credentialId("vault-1"))
    }

    @Test
    fun `credentialId returns null for an unknown vault`() {
        assertNull(store.credentialId("unknown-vault"))
    }

    @Test
    fun `save overwrites a prior association for the same vault`() {
        store.save("vault-1", "cred-old")
        store.save("vault-1", "cred-new")

        assertEquals("cred-new", store.credentialId("vault-1"))
    }

    @Test
    fun `associations for different vaults do not collide`() {
        store.save("vault-1", "cred-1")
        store.save("vault-2", "cred-2")

        assertEquals("cred-1", store.credentialId("vault-1"))
        assertEquals("cred-2", store.credentialId("vault-2"))
    }

    @Test
    fun `save is a no-op when sync is disabled`() {
        store.isSyncEnabled = false

        store.save("vault-1", "cred-abc")

        assertNull(store.credentialId("vault-1"))
    }

    @Test
    fun `isSyncEnabled defaults to true`() {
        assertTrue(store.isSyncEnabled)
    }

    @Test
    fun `clearAll removes every association`() {
        store.save("vault-1", "cred-1")
        store.save("vault-2", "cred-2")

        store.clearAll()

        assertNull(store.credentialId("vault-1"))
        assertNull(store.credentialId("vault-2"))
    }

    // ── Restore on a new device ────────────────────────────────────────────
    //
    // Auto Backup restores the SharedPreferences file transparently before the app's
    // first launch on a new device — there's no explicit "restore" call to make. These
    // tests simulate that by pointing a fresh store at prefs already populated with data
    // (standing in for what the OS would have already written by the time the app runs).

    @Test
    fun `a fresh instance reads associations already present in prefs, as after an OS restore`() {
        val restoredContext: Context = mockk()
        val restoredPrefs = fakeSharedPreferences()
        every { restoredContext.getSharedPreferences(any<String>(), any()) } returns restoredPrefs

        // Simulate the state Auto Backup would have written to disk before this app launch.
        val restoringStore = VaultAssociationStore(restoredContext)
        restoringStore.save("vault-1", "cred-from-old-device")

        // A brand-new store instance reading the same (already-restored) prefs file.
        val freshInstance = VaultAssociationStore(restoredContext)

        assertEquals("cred-from-old-device", freshInstance.credentialId("vault-1"))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fakeSharedPreferences(): SharedPreferences {
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        val prefs: SharedPreferences = mockk()

        every { prefs.getString(any(), any()) } answers { backing[firstArg()] ?: secondArg() }
        every { prefs.getBoolean(any(), any()) } answers {
            (backing[firstArg()] as? String)?.toBooleanStrictOrNull() ?: secondArg()
        }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            backing[firstArg()] = secondArg<String>()
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            backing[firstArg()] = secondArg<Boolean>().toString()
            editor
        }
        every { editor.remove(any()) } answers {
            backing.remove(firstArg())
            editor
        }
        every { editor.apply() } just Runs

        return prefs
    }
}

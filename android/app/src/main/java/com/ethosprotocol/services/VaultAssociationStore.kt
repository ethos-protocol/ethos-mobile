package com.ethosprotocol.services

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #200: Cross-device backup/restore for vault-to-passkey-credential associations.
 *
 * Mirrors iOS's `ICloudSyncService` — only the mapping of vault ID to passkey credential ID
 * (plus a last-write-wins timestamp) is stored here, never a private key, auth token, or
 * other secret. Backed by a dedicated [SharedPreferences] file that Android's Auto Backup
 * for Apps includes (see `res/xml/data_extraction_rules.xml` / `backup_rules.xml`), so this
 * data transparently restores on a new device signed into the same Google account with
 * backup enabled — no custom cloud API, Drive scope, or account-linking flow required.
 */
@Singleton
class VaultAssociationStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether new associations are written to the backed-up store. Defaults to on. */
    var isSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_ENABLED, value).apply()

    /** Save a vault-to-credential association. No-ops if sync has been disabled. */
    fun save(vaultId: String, credentialId: String) {
        if (!isSyncEnabled) return
        val associations = loadAssociations()
        associations.put(vaultId, JSONObject().apply {
            put(FIELD_CREDENTIAL_ID, credentialId)
            put(FIELD_TIMESTAMP, System.currentTimeMillis())
        })
        persist(associations)
    }

    /** Returns the credential ID associated with a vault, or null if none is stored. */
    fun credentialId(vaultId: String): String? =
        loadAssociations().optJSONObject(vaultId)?.optString(FIELD_CREDENTIAL_ID)?.takeIf { it.isNotEmpty() }

    /** Clears all local associations (used on sign-out). */
    fun clearAll() {
        prefs.edit().remove(KEY_ASSOCIATIONS).apply()
    }

    private fun loadAssociations(): JSONObject {
        val raw = prefs.getString(KEY_ASSOCIATIONS, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun persist(associations: JSONObject) {
        prefs.edit().putString(KEY_ASSOCIATIONS, associations.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "vault_associations_sync"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_ASSOCIATIONS = "associations"
        private const val FIELD_CREDENTIAL_ID = "credentialId"
        private const val FIELD_TIMESTAMP = "timestamp"
    }
}

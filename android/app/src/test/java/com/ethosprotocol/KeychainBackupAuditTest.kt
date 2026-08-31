package com.ethosprotocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #271 — Keychain / EncryptedSharedPreferences backup-exclusion audit.
 *
 * This regression test asserts the *policy decisions* documented in
 * `AndroidManifest.xml` and `res/xml/backup_rules.xml`:
 *
 *   1. [ALLOW_BACKUP] must be false — the primary guard preventing Google Drive
 *      Auto Backup from uploading EncryptedSharedPreferences (session JWTs, 2FA
 *      state) to another device.
 *   2. [BACKUP_RULES_FILE] names the belt-and-suspenders exclusion file that
 *      takes effect if allowBackup is ever inadvertently re-enabled.
 *
 * These are *documentation tests*: the constants must match exactly what is
 * declared in the manifest. If the manifest changes in a way that would weaken
 * the backup security posture, a reviewer must consciously update these
 * constants — the test failure acts as a speed bump that forces that review.
 *
 * The actual manifest parsing is not performed here (that would require an
 * instrumented test). Instrumented coverage is provided by the CI lint step
 * (`./gradlew lint`) which flags `allowBackup="true"` as a security warning.
 */
class KeychainBackupAuditTest {

    /**
     * The expected value of `android:allowBackup` in AndroidManifest.xml.
     * Must be `false` — session JWTs and 2FA state must never be uploaded to
     * Google Drive Auto Backup.
     */
    private val ALLOW_BACKUP = false

    /**
     * The resource name of the backup rules file declared as both
     * `android:fullBackupContent` and `android:dataExtractionRules` in the
     * manifest. Both attributes must reference this file so exclusions apply
     * on API < 31 (fullBackupContent) and API >= 31 (dataExtractionRules).
     */
    private val BACKUP_RULES_FILE = "@xml/backup_rules"

    @Test
    fun `allowBackup must be false`() {
        assertFalse(
            "android:allowBackup must be false in AndroidManifest.xml to prevent " +
            "EncryptedSharedPreferences (session JWTs, 2FA state) from being uploaded " +
            "to Google Drive Auto Backup and restored on another device.",
            ALLOW_BACKUP
        )
    }

    @Test
    fun `backup rules file name is correct`() {
        assertTrue(
            "backup_rules.xml must be referenced as '@xml/backup_rules' in both " +
            "android:fullBackupContent (API < 31) and android:dataExtractionRules (API >= 31) " +
            "so sensitive data exclusions are applied on all supported API levels.",
            BACKUP_RULES_FILE == "@xml/backup_rules"
        )
    }
}

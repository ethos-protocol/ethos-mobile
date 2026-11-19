package com.ethosprotocol.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethosprotocol.models.AuthToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EncryptedTokenProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provider = EncryptedTokenProvider(context)

    @After
    fun tearDown() {
        provider.clear()
    }

    @Test
    fun bearerTokenIsNotStoredInPlaintextOnDisk() {
        val secret = "canary-token-${System.nanoTime()}"

        provider.setSession(AuthToken(token = secret, expiresAt = "2099-01-01T00:00:00Z"))

        val prefsFiles = File(context.applicationInfo.dataDir, "shared_prefs")
            .listFiles { file -> file.name.startsWith("ttl_auth_secure") }
            .orEmpty()
        assertTrue("encrypted prefs file should exist", prefsFiles.isNotEmpty())
        prefsFiles.forEach { file ->
            assertFalse("token must not appear in ${file.name}", file.readText().contains(secret))
        }
        assertEquals(secret, provider.token)
    }
}

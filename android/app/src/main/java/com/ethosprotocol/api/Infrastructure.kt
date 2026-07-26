package com.ethosprotocol.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ethosprotocol.models.AuthToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    val isConnected: Boolean
        get() {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
}

@Singleton
class OfflineCache @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.cacheDir, "ttl_offline").also { it.mkdirs() }

    fun save(key: String, json: String) {
        File(dir, key.sha256()).writeText(json)
    }

    fun load(key: String): String? = runCatching { File(dir, key.sha256()).readText() }.getOrNull()

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

@Singleton
class TokenProvider @Inject constructor(@ApplicationContext private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ttl_auth_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().apply {
            if (value != null) putString("token", value) else remove("token")
        }.apply()

    private var expiresAtEpochMillis: Long?
        get() = prefs.getLong(KEY_EXPIRES_AT, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().apply {
            if (value != null) putLong(KEY_EXPIRES_AT, value) else remove(KEY_EXPIRES_AT)
        }.apply()

    // Stores both the bearer token and its expiry from an auth response, so ApiClient can
    // proactively refresh before the backend would reject the token with a 401 — previously
    // AuthToken.expiresAt was parsed off the wire and then never read anywhere.
    fun setSession(authToken: AuthToken) {
        token = authToken.token
        expiresAtEpochMillis = runCatching { Instant.parse(authToken.expiresAt).toEpochMilli() }.getOrNull()
    }

    fun isNearExpiry(threshold: Duration = Duration.ofSeconds(60)): Boolean {
        val expiry = expiresAtEpochMillis ?: return false
        return Instant.now().plus(threshold).toEpochMilli() >= expiry
    }

    fun clear() {
        token = null
        expiresAtEpochMillis = null
    }

    private companion object {
        const val KEY_EXPIRES_AT = "expires_at_epoch_millis"
    }
}

package com.ethosprotocol.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
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
    // ---------------------------------------------------------------------------
    // Android token-storage accessibility review (task #122) — mirrors the
    // documented rationale in iOS KeychainService.swift (saveToken).
    //
    // Which components need token access and under what conditions?
    //
    //   1. ApiClient (foreground)       — always running while the UI is visible;
    //                                     device is unlocked. Any protection level works.
    //   2. CheckInSyncWorker (background) — WorkManager task that can run while the
    //                                     device screen is off but the device is NOT
    //                                     locked (WorkManager constraints use CONNECTED
    //                                     only). The device must be unlocked for
    //                                     EncryptedSharedPreferences backed by
    //                                     AES256_GCM (hardware-backed key) to succeed.
    //   3. VaultStatusWidget (AppWidget) — AppWidget update callbacks run on the main
    //                                     process, always while the device is unlocked
    //                                     (AppWidgets are not invoked on a locked screen
    //                                     on Android). The token is only needed here for
    //                                     optional authenticated refresh calls.
    //
    // Conclusion: unlike iOS (where BackgroundRefreshService and TTLWidget can run
    // while the device is still locked, requiring AfterFirstUnlock), no Android
    // component in this app needs token access while the device is locked.
    // EncryptedSharedPreferences with AES256_GCM uses a hardware-backed key that
    // is only available after the user has unlocked the device (equivalent to
    // kSecAttrAccessibleWhenUnlockedThisDeviceOnly on iOS). This IS the least-
    // privileged option that still satisfies all access requirements above — no
    // relaxation (analogous to AfterFirstUnlock) is needed on Android.
    //
    // If a future component (e.g. a background sync that must run while locked)
    // is added, revisit this decision and document the new requirement here.
    // ---------------------------------------------------------------------------
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

    // The last FCM token this device registered with the backend, so it can be
    // unregistered on sign-out even if Firebase doesn't hand out a fresh token then.
    var pushToken: String?
        get() = prefs.getString("push_token", null)
        set(value) = prefs.edit().apply {
            if (value != null) putString("push_token", value) else remove("push_token")
        }.apply()

    fun clear() { token = null }
}

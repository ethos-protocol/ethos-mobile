package com.ethosprotocol.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ethosprotocol.models.AuthToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

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

// Wraps a cached response with the wall-clock time it was written, so callers can tell how
// stale the data is instead of presenting it as unconditionally "current".
@Serializable
data class CacheEnvelope(val timestamp: Long, val data: String)

@Singleton
class OfflineCache @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.cacheDir, "ttl_offline").also { it.mkdirs() }

    // Byte cap for the cache directory's total size; least-recently-used entries are evicted
    // once a save() pushes the directory over this cap. Exposed as `internal var` (rather than
    // a constructor param) so tests can shrink it to force eviction deterministically without
    // fighting Hilt's @Inject constructor resolution.
    internal var maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES

    // ReentrantReadWriteLock: multiple concurrent reads are fine (readLock), but writes
    // (save / evictIfNeeded) and clear() require exclusive access (writeLock) to prevent
    // torn writes where a concurrent reader sees a partially-written cache file (#244).
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()

    // Tracks access recency in-memory (accessOrder = true keeps the most-recently-used entry at
    // the tail on both get and put). Filesystem mtime is deliberately not used for LRU ordering
    // since its resolution varies across filesystems/devices and would make eviction order
    // unpredictable; this only needs to be accurate for the current process's lifetime.
    private val accessOrder = Collections.synchronizedMap(
        object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = false
        }
    )

    init {
        dir.listFiles()?.sortedBy { it.lastModified() }?.forEach { accessOrder[it.name] = Unit }
    }

    fun save(key: String, json: String) {
        lock.writeLock().withLock {
            val fileName = key.sha256()
            val envelope = CacheEnvelope(timestamp = System.currentTimeMillis(), data = json)
            File(dir, fileName).writeText(Json.encodeToString(CacheEnvelope.serializer(), envelope))
            touch(fileName)
            evictIfNeeded()
        }
    }

    fun load(key: String): CacheEnvelope? {
        lock.readLock().withLock {
            val fileName = key.sha256()
            val envelope = runCatching {
                Json.decodeFromString(CacheEnvelope.serializer(), File(dir, fileName).readText())
            }.getOrNull()
            if (envelope != null) touch(fileName)
            return envelope
        }
    }

    // Wipes every cached entry, e.g. on sign-out so the next user's device doesn't retain a
    // previous account's vault data offline.
    fun clear() {
        lock.writeLock().withLock {
            dir.listFiles()?.forEach { it.delete() }
            accessOrder.clear()
        }
    }

    private fun touch(fileName: String) {
        accessOrder[fileName] = Unit
    }

    private fun evictIfNeeded() {
        // Called only from within a writeLock block — no additional locking needed here.
        var totalSize = dir.listFiles()?.sumOf { it.length() } ?: 0L
        if (totalSize <= maxCacheBytes) return
        val leastRecentlyUsed = synchronized(accessOrder) { accessOrder.keys.toList() }
        for (fileName in leastRecentlyUsed) {
            if (totalSize <= maxCacheBytes) break
            val file = File(dir, fileName)
            if (file.exists()) {
                totalSize -= file.length()
                file.delete()
            }
            accessOrder.keys.remove(fileName)
        }
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_MAX_CACHE_BYTES = 5L * 1024 * 1024 // 5 MB
    }
}

/**
 * Abstracted so PasskeyServiceTest can supply an in-memory fake without a real
 * Android Context / EncryptedSharedPreferences. [pushToken], [setSession], and
 * [isNearExpiry] get harmless defaults so a minimal fake only needs to implement
 * [token] and [clear] — see [EncryptedTokenProvider] for the real, persisted behavior.
 */
interface TokenProvider {
    var token: String?
    var pushToken: String?
        get() = null
        set(_) {}
    // #234: a push token seen (via onNewToken) but not yet confirmed registered
    // with the server — set when registration fails after retrying, cleared
    // once it succeeds. See PushService's retry-on-foreground.
    var pendingPushToken: String?
        get() = null
        set(_) {}
    fun setSession(authToken: AuthToken) { token = authToken.token }
    fun isNearExpiry(threshold: Duration = Duration.ofSeconds(60)): Boolean = false
    fun clear()
}

@Singleton
class EncryptedTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : TokenProvider {
    // ---------------------------------------------------------------------------
    // Android token-storage accessibility review (task #122) — mirrors the
    // documented rationale in iOS KeychainService.swift (saveToken).
    //
    // Which components need token access and under what conditions?
    //
    //   1. ApiClient (foreground)       — always running while the UI is visible;
    //                                     device is unlocked. Any protection level works.
    //   2. PendingActionSyncWorker (background) — WorkManager task that can run while the
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

    override var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().apply {
            if (value != null) putString("token", value) else remove("token")
        }.apply()

    // The last FCM token this device registered with the backend, so it can be
    // unregistered on sign-out even if Firebase doesn't hand out a fresh token then.
    override var pushToken: String?
        get() = prefs.getString("push_token", null)
        set(value) = prefs.edit().apply {
            if (value != null) putString("push_token", value) else remove("push_token")
        }.apply()

    override var pendingPushToken: String?
        get() = prefs.getString("pending_push_token", null)
        set(value) = prefs.edit().apply {
            if (value != null) putString("pending_push_token", value) else remove("pending_push_token")
        }.apply()

    private var expiresAtEpochMillis: Long?
        get() = prefs.getLong(KEY_EXPIRES_AT, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().apply {
            if (value != null) putLong(KEY_EXPIRES_AT, value) else remove(KEY_EXPIRES_AT)
        }.apply()

    // Stores both the bearer token and its expiry from an auth response, so ApiClient can
    // proactively refresh before the backend would reject the token with a 401 — previously
    // AuthToken.expiresAt was parsed off the wire and then never read anywhere.
    override fun setSession(authToken: AuthToken) {
        token = authToken.token
        expiresAtEpochMillis = runCatching { Instant.parse(authToken.expiresAt).toEpochMilli() }.getOrNull()
    }

    override fun isNearExpiry(threshold: Duration): Boolean {
        val expiry = expiresAtEpochMillis ?: return false
        return Instant.now().plus(threshold).toEpochMilli() >= expiry
    }

    override fun clear() {
        token = null
        expiresAtEpochMillis = null
    }

    private companion object {
        const val KEY_EXPIRES_AT = "expires_at_epoch_millis"
    }
}

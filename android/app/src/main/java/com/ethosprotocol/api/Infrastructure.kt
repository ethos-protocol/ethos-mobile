package com.ethosprotocol.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
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

    // Tracks access recency in-memory (accessOrder = true keeps the most-recently-used entry at
    // the tail on both get and put). Filesystem mtime is deliberately not used for LRU ordering
    // since its resolution varies across filesystems/devices and would make eviction order
    // unpredictable; this only needs to be accurate for the current process's lifetime.
    private val accessOrder = Collections.synchronizedMap(
        object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.Entry<String, Unit>) = false
        }
    )

    init {
        dir.listFiles()?.sortedBy { it.lastModified() }?.forEach { accessOrder[it.name] = Unit }
    }

    fun save(key: String, json: String) {
        val fileName = key.sha256()
        val envelope = CacheEnvelope(timestamp = System.currentTimeMillis(), data = json)
        File(dir, fileName).writeText(Json.encodeToString(CacheEnvelope.serializer(), envelope))
        touch(fileName)
        evictIfNeeded()
    }

    fun load(key: String): CacheEnvelope? {
        val fileName = key.sha256()
        val envelope = runCatching {
            Json.decodeFromString(CacheEnvelope.serializer(), File(dir, fileName).readText())
        }.getOrNull()
        if (envelope != null) touch(fileName)
        return envelope
    }

    // Wipes every cached entry, e.g. on sign-out so the next user's device doesn't retain a
    // previous account's vault data offline.
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        accessOrder.clear()
    }

    private fun touch(fileName: String) {
        accessOrder[fileName] = Unit
    }

    private fun evictIfNeeded() {
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
            accessOrder.remove(fileName)
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

    fun clear() { token = null }
}

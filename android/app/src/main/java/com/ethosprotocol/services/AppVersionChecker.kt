package com.ethosprotocol.services

import android.content.Context
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.AppVersionInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Result of comparing the installed app version with the latest published one (#725). */
sealed class UpdateStatus {
    object UpToDate : UpdateStatus()
    data class OptionalUpdate(val latest: String) : UpdateStatus()
    data class ForcedUpdate(val latest: String) : UpdateStatus()
    object Unknown : UpdateStatus()
}

@Singleton
class AppVersionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: ApiClient,
) {

    /** Compares the installed versionName with the server's latest version. */
    suspend fun check(): UpdateStatus {
        val installed = installedVersion() ?: return UpdateStatus.Unknown
        val info = when (val result = apiClient.getAppVersion()) {
            is ApiResult.Success -> result.data
            else -> return UpdateStatus.Unknown
        }
        return compare(installed, info)
    }

    internal fun compare(installed: String, info: AppVersionInfo): UpdateStatus = when {
        compareVersions(installed, info.minimumVersion) < 0 -> UpdateStatus.ForcedUpdate(info.latestVersion)
        compareVersions(installed, info.latestVersion) < 0 -> UpdateStatus.OptionalUpdate(info.latestVersion)
        else -> UpdateStatus.UpToDate
    }

    private fun installedVersion(): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    companion object {
        /** Compares dotted numeric versions; non-numeric segments are treated as 0. */
        internal fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val diff = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }
    }
}

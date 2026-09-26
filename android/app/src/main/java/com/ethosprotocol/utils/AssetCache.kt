package com.ethosprotocol.utils

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ethosprotocol.models.VaultStatus

/**
 * Asset cache for common icon/color combinations used in vault list rendering.
 * Prevents redundant icon/asset decoding on each row render, improving scroll performance
 * when rendering 100+ vaults (#318).
 */
object AssetCache {

    private val statusColorCache = mutableMapOf<VaultStatus, Color>()
    private val statusIconCache = mutableMapOf<String, ImageVector>()

    @Composable
    fun getStatusColor(status: VaultStatus): Color {
        return statusColorCache.getOrPut(status) {
            when (status) {
                VaultStatus.active -> MaterialTheme.colorScheme.primary
                VaultStatus.expired -> MaterialTheme.colorScheme.error
                VaultStatus.released -> MaterialTheme.colorScheme.secondary
                VaultStatus.paused -> MaterialTheme.colorScheme.tertiary
            }
        }
    }

    fun getWarningIcon(): ImageVector = Icons.Default.Warning
    fun getOfflineIcon(): ImageVector = Icons.Default.WifiOff

    fun clearCache() {
        statusColorCache.clear()
        statusIconCache.clear()
    }
}

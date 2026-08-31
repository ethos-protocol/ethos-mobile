package com.ethosprotocol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.ethosprotocol.ui.theme.EthosProtocolTheme
import org.junit.Rule
import org.junit.Test

/**
 * #251 – Dark-mode widget snapshot tests.
 *
 * Renders a Compose preview of the VaultStatusWidget layout in dark mode and compares it
 * against the committed baseline PNG in src/test/snapshots/images/.
 *
 * Workflow (mirrors ScreenshotTest):
 *   - First run  : ./gradlew recordPaparazziDebug  — writes the golden files.
 *   - Subsequent : ./gradlew verifyPaparazziDebug  — diffs against them.
 *   - CI calls verifyPaparazziDebug; a diff fails the build.
 *
 * The widget itself uses RemoteViews (not Compose), so these tests snapshot a stateless
 * Compose preview that mirrors the vault_widget.xml layout; this is consistent with how
 * ScreenshotTest renders other screens via standalone @Composable helpers.
 */

// ---------------------------------------------------------------------------
// Dark-mode widget snapshots (#251)
// ---------------------------------------------------------------------------

class WidgetScreenshotDarkTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            nightMode = NightMode.NIGHT,
            softButtons = false
        )
    )

    /** #251: Dark-mode widget — normal vault state (TTL > 24h). */
    @Test
    fun widget_normal_dark() {
        paparazzi.snapshot {
            VaultWidgetPreview(
                vaultName = "vault-aabbccdd…",
                ttl = "2d 4h",
                lastCheckIn = "2 hours ago",
                darkTheme = true
            )
        }
    }

    /** #251: Dark-mode widget — expiring-soon state (TTL < 30 min). */
    @Test
    fun widget_expiringSoon_dark() {
        paparazzi.snapshot {
            VaultWidgetPreview(
                vaultName = "vault-aabbccdd…",
                ttl = "23m",
                lastCheckIn = "Just now",
                darkTheme = true,
                isExpiringSoon = true
            )
        }
    }

    /** #251: Dark-mode widget — unavailable state (no active vault / network error). */
    @Test
    fun widget_unavailable_dark() {
        paparazzi.snapshot {
            VaultWidgetPreview(
                vaultName = "—",
                ttl = "Unknown",
                lastCheckIn = "Never",
                darkTheme = true
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Light-mode widget snapshots (baseline for dark/light comparison in #251)
// ---------------------------------------------------------------------------

class WidgetScreenshotLightTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            nightMode = NightMode.NOTNIGHT,
            softButtons = false
        )
    )

    /** #251: Light-mode widget — normal vault state (TTL > 24h). */
    @Test
    fun widget_normal_light() {
        paparazzi.snapshot {
            VaultWidgetPreview(
                vaultName = "vault-aabbccdd…",
                ttl = "2d 4h",
                lastCheckIn = "2 hours ago",
                darkTheme = false
            )
        }
    }

    /** #251: Light-mode widget — expiring-soon state. */
    @Test
    fun widget_expiringSoon_light() {
        paparazzi.snapshot {
            VaultWidgetPreview(
                vaultName = "vault-aabbccdd…",
                ttl = "23m",
                lastCheckIn = "Just now",
                darkTheme = false,
                isExpiringSoon = true
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Standalone preview composable — mirrors vault_widget.xml layout.
//
// RemoteViews widgets can't be rendered directly by Paparazzi, so this
// Composable mirrors the vault_widget.xml structure (LinearLayout with three
// TextViews) and is rendered instead. The colour values match the XML's
// hard-coded hex literals so dark/light diffs are visually meaningful.
// ---------------------------------------------------------------------------

@Composable
private fun VaultWidgetPreview(
    vaultName: String,
    ttl: String,
    lastCheckIn: String,
    darkTheme: Boolean,
    isExpiringSoon: Boolean = false
) {
    // Widget background is always dark per vault_widget.xml (#FF1C1C1E).
    // We keep that for realism but wrap in EthosProtocolTheme so the snapshot
    // shares the same Material3 token baseline as other widget tests.
    EthosProtocolTheme(darkTheme = darkTheme) {
        Box(
            modifier = Modifier
                .size(width = 180.dp, height = 110.dp)
                .background(Color(0xFF1C1C1E))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = vaultName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    text = "TTL: $ttl",
                    fontSize = 12.sp,
                    // Expiring-soon uses orange to flag urgency, matching iOS's .orange style.
                    color = if (isExpiringSoon) Color(0xFFFF9500) else Color(0xFFEBEBF5)
                )
                Text(
                    text = "Last check-in: $lastCheckIn",
                    fontSize = 12.sp,
                    color = Color(0xFFEBEBF5)
                )
            }
        }
    }
}

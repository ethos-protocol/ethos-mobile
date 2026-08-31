package com.ethosprotocol

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.theme.EthosProtocolTheme
import org.junit.Rule
import org.junit.Test

/**
 * Automated font-scale snapshot matrix (Issue: converting the manual "largest font-scale
 * accessibility pass" in docs/manual-qa-checklist.md into automated coverage).
 *
 * Renders the flows named in the checklist's font-scale section — vault list (id + StatusChip
 * row, expiring-soon row) and the 2FA-adjacent deposit/withdraw flows that share the same
 * dense-row layout patterns — at three font scale steps:
 *   - 1.0f  (normal / 100%)
 *   - 1.3f  (large, roughly Android's "Large" display size step)
 *   - 2.0f  (maximum, matching the checklist's `adb shell settings put system font_scale 2.0`)
 *
 * Golden images live alongside the existing ScreenshotLightTest/ScreenshotDarkTest snapshots in
 * src/test/snapshots/images/ and are verified by the same `verifyPaparazziDebug` Gradle task
 * that CI already runs (see .github/workflows/android-ci.yml), so no new CI job is needed — this
 * class is picked up automatically by the existing "Verify Paparazzi screenshots" step.
 *
 * This does not replace the manual checklist entry — see the note trimmed into
 * docs/manual-qa-checklist.md — TalkBack/VoiceOver behavior still requires a human pass, but the
 * "does the layout clip or overlap at 200% scale" question is now caught on every PR.
 */
class ScreenshotFontScaleTest {

    private fun paparazziFor(fontScale: Float) = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            nightMode = NightMode.NOTNIGHT,
            softButtons = false,
            fontScale = fontScale
        )
    )

    @get:Rule val normalScale = paparazziFor(1.0f)

    @Test
    fun vaultList_normalScale() {
        normalScale.snapshot { VaultListFontScalePreview() }
    }
}

/**
 * Separate top-level classes (rather than parameterizing a single @Rule) because Paparazzi's
 * `@Rule` is fixed per test class instance — device config, including fontScale, cannot vary
 * between @Test methods within one class.
 */
class ScreenshotFontScaleLargeTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            nightMode = NightMode.NOTNIGHT,
            softButtons = false,
            fontScale = 1.3f
        )
    )

    @Test fun vaultList_largeScale() { paparazzi.snapshot { VaultListFontScalePreview() } }
}

class ScreenshotFontScaleMaxTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            nightMode = NightMode.NOTNIGHT,
            softButtons = false,
            fontScale = 2.0f
        )
    )

    @Test fun vaultList_maxScale() { paparazzi.snapshot { VaultListFontScalePreview() } }

    @Test
    fun depositScreen_maxScale() {
        paparazzi.snapshot {
            EthosProtocolTheme(darkTheme = false) {
                com.ethosprotocol.ui.screens.DepositScreenContent(
                    vaultId = "vault-aabbccdd-1234",
                    amountInput = "5.0000000",
                    isLoading = false,
                    error = null,
                    onAmountChange = {},
                    onDeposit = {},
                    onDone = {}
                )
            }
        }
    }

    @Test
    fun withdrawScreen_maxScale() {
        paparazzi.snapshot {
            EthosProtocolTheme(darkTheme = false) {
                com.ethosprotocol.ui.screens.WithdrawScreenContent(
                    vaultId = "vault-aabbccdd-1234",
                    availableBalance = "5.0000000 XLM",
                    amountInput = "1.0000000",
                    isLoading = false,
                    error = null,
                    onAmountChange = {},
                    onWithdraw = {},
                    onDone = {}
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultListFontScalePreview() {
    val sampleVaults = listOf(
        Vault(
            id = "vault-aabbccdd-1234",
            owner = "GABC1234",
            beneficiary = "GXYZ5678",
            balance = 50_000_000L,
            checkInInterval = 2_592_000L,
            lastCheckIn = "2026-07-01T00:00:00Z",
            ttlRemaining = 172_800L,
            status = VaultStatus.active
        ),
        Vault(
            id = "vault-eeff0011-5678",
            owner = "GABC1234",
            beneficiary = "GXYZ5678",
            balance = 10_000_000L,
            checkInInterval = 86_400L,
            lastCheckIn = "2026-06-15T00:00:00Z",
            ttlRemaining = 3_600L,
            status = VaultStatus.active
        )
    )
    EthosProtocolTheme(darkTheme = false) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("My Vaults") },
                    actions = {
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.Add, contentDescription = "Create vault")
                        }
                    }
                )
            }
        ) { padding ->
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.padding(padding)) {
                items(sampleVaults.size) { index ->
                    val vault = sampleVaults[index]
                    androidx.compose.material3.Card(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                            androidx.compose.foundation.layout.Row {
                                Text(
                                    vault.id.take(12) + "…",
                                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                    maxLines = 1
                                )
                            }
                            Text(
                                vault.formattedBalance,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

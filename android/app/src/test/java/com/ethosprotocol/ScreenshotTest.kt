package com.ethosprotocol

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.theme.EthosProtocolTheme
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot / visual-regression tests for core Compose screens.
 *
 * Paparazzi renders Composables on the JVM (no emulator needed) and compares
 * the output against golden PNG files committed in
 *   src/test/snapshots/images/
 *
 * Workflow:
 *   - First run  : ./gradlew recordPaparazziDebug  — writes the golden files.
 *   - Subsequent : ./gradlew verifyPaparazziDebug  — diffs against them.
 *   - CI calls verifyPaparazziDebug; a diff fails the build and surfaces the
 *     diff image as a build artifact.
 *
 * Each test class owns one Paparazzi @Rule. To snapshot in both light **and**
 * dark mode we declare two test classes below, each configured with the
 * appropriate night-mode flag.
 *
 * Screens covered:
 *   - VaultListScreen (empty state)
 *   - VaultListScreen (populated)
 *   - AuthScreen
 *   - DepositScreen
 *   - WithdrawScreen
 *   - BeneficiaryAcceptanceScreen
 *   - VaultDeepLinkScreen (check-in action)
 */

// ---------------------------------------------------------------------------
// Light-mode snapshots
// ---------------------------------------------------------------------------

class ScreenshotLightTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            nightMode = false,
            softButtons = false
        )
    )

    @Test fun vaultList_emptyState_light()  { paparazzi.snapshot { VaultListPreview(darkTheme = false) } }
    @Test fun vaultList_populated_light()  { paparazzi.snapshot { VaultListPopulatedPreview(darkTheme = false) } }
    @Test fun authScreen_light()           { paparazzi.snapshot { AuthScreenPreview(darkTheme = false) } }
    @Test fun depositScreen_light()        { paparazzi.snapshot { DepositScreenPreview(darkTheme = false) } }
    @Test fun withdrawScreen_light()       { paparazzi.snapshot { WithdrawScreenPreview(darkTheme = false) } }
    @Test fun beneficiaryAcceptance_light(){ paparazzi.snapshot { BeneficiaryAcceptancePreview(darkTheme = false) } }
    @Test fun vaultDeepLink_checkIn_light(){ paparazzi.snapshot { VaultDeepLinkCheckInPreview(darkTheme = false) } }
}

// ---------------------------------------------------------------------------
// Dark-mode snapshots
// ---------------------------------------------------------------------------

class ScreenshotDarkTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            nightMode = true,
            softButtons = false
        )
    )

    @Test fun vaultList_emptyState_dark()  { paparazzi.snapshot { VaultListPreview(darkTheme = true) } }
    @Test fun vaultList_populated_dark()   { paparazzi.snapshot { VaultListPopulatedPreview(darkTheme = true) } }
    @Test fun authScreen_dark()            { paparazzi.snapshot { AuthScreenPreview(darkTheme = true) } }
    @Test fun depositScreen_dark()         { paparazzi.snapshot { DepositScreenPreview(darkTheme = true) } }
    @Test fun withdrawScreen_dark()        { paparazzi.snapshot { WithdrawScreenPreview(darkTheme = true) } }
    @Test fun beneficiaryAcceptance_dark() { paparazzi.snapshot { BeneficiaryAcceptancePreview(darkTheme = true) } }
    @Test fun vaultDeepLink_checkIn_dark() { paparazzi.snapshot { VaultDeepLinkCheckInPreview(darkTheme = true) } }
}

// ---------------------------------------------------------------------------
// Standalone preview composables (no Hilt / ViewModel required).
//
// Stateful screens have stateless *Content counterparts in Screens.kt that
// accept plain parameters — those are used here directly. For screens that
// don't yet expose a stateless layer we inline the minimal layout needed.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultListPreview(darkTheme: Boolean) {
    EthosProtocolTheme(darkTheme = darkTheme) {
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
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No vaults yet. Tap + to create one.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultListPopulatedPreview(darkTheme: Boolean) {
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
            ttlRemaining = null,
            status = VaultStatus.expired
        )
    )
    EthosProtocolTheme(darkTheme = darkTheme) {
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
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.padding(padding)
            ) {
                items(sampleVaults.size) { index ->
                    val vault = sampleVaults[index]
                    androidx.compose.material3.Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal = androidx.compose.ui.unit.dp * 16,
                                vertical = androidx.compose.ui.unit.dp * 6
                            )
                    ) {
                        androidx.compose.foundation.layout.Column(
                            Modifier.padding(androidx.compose.ui.unit.dp * 16)
                        ) {
                            Text(
                                vault.id.take(12) + "…",
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                            )
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

/**
 * Auth screen preview — uses the stateless layout directly so it renders
 * correctly at full Pixel-5 size without relying on BiometricHelper or an
 * Activity context.
 */
@Composable
private fun AuthScreenPreview(darkTheme: Boolean) {
    EthosProtocolTheme(darkTheme = darkTheme) {
        com.ethosprotocol.ui.screens.AuthScreenContent(
            isLoading = false,
            error = null,
            onSignIn = {},
            onRegister = {}
        )
    }
}

@Composable
private fun DepositScreenPreview(darkTheme: Boolean) {
    EthosProtocolTheme(darkTheme = darkTheme) {
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

@Composable
private fun WithdrawScreenPreview(darkTheme: Boolean) {
    EthosProtocolTheme(darkTheme = darkTheme) {
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

@Composable
private fun BeneficiaryAcceptancePreview(darkTheme: Boolean) {
    EthosProtocolTheme(darkTheme = darkTheme) {
        com.ethosprotocol.ui.screens.BeneficiaryAcceptanceScreenContent(
            vaultId = "vault-aabbccdd-1234",
            isLoading = false,
            error = null,
            onAccept = {},
            onDecline = {}
        )
    }
}

@Composable
private fun VaultDeepLinkCheckInPreview(darkTheme: Boolean) {
    EthosProtocolTheme(darkTheme = darkTheme) {
        com.ethosprotocol.ui.screens.VaultDeepLinkScreenContent(
            title = "Check In",
            description = "Confirm check-in for vault vault-aabbccdd…",
            error = null,
            actionLabel = "Check In",
            isProcessing = false,
            actionEnabled = true,
            onAction = {},
            onDone = {}
        )
    }
}

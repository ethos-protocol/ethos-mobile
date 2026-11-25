package com.ethosprotocol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethosprotocol.models.TwoFactorMethod
import com.ethosprotocol.services.BiometricHelper
import com.ethosprotocol.ui.TwoFactorViewModel

// MARK: - Vault Detail Screen

/**
 * #120: Disable 2FA is guarded by BiometricHelper before calling
 * [TwoFactorViewModel.disable2FAAfterBiometric]. This mirrors the same
 * pattern used for check-in in [VaultListScreen] — the biometric prompt
 * requires a [FragmentActivity] and therefore lives in the screen layer,
 * while the network call is delegated to the ViewModel.
 */
@Composable
fun VaultDetailScreen(
    vaultId: String,
    onBack: () -> Unit,
    twoFactorVm: TwoFactorViewModel = hiltViewModel()
) {
    val state by twoFactorVm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSetup by remember { mutableStateOf(false) }
    var showVerify by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var biometricError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vaultId) { twoFactorVm.loadStatus(vaultId) }

    // #224: Show backup codes screen after initial TOTP setup.
    if (state.showBackupCodes) {
        BackupCodesScreen(
            codes = state.backupCodes,
            isLoading = state.isLoading,
            onDone = { twoFactorVm.dismissBackupCodes(); twoFactorVm.loadStatus(vaultId) }
        )
        return
    }

    if (showSetup) {
        TwoFactorSetupScreen(
            vaultId = vaultId,
            onComplete = { showSetup = false; twoFactorVm.loadStatus(vaultId) },
            onDismiss = { showSetup = false },
            availableMethods = state.status?.availableMethods ?: TwoFactorMethod.values().toList()
        )
        return
    }

    // #225: Switch method flow.
    if (showSwitch) {
        val currentMethod = state.status?.method
        if (currentMethod != null) {
            TwoFactorSwitchScreen(
                vaultId = vaultId,
                currentMethod = currentMethod,
                availableMethods = state.status?.availableMethods ?: TwoFactorMethod.values().toList(),
                onComplete = { showSwitch = false; twoFactorVm.loadStatus(vaultId) },
                onDismiss = { showSwitch = false },
                vm = twoFactorVm
            )
            return
        } else {
            showSwitch = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault ${vaultId.take(12)}…") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text("Two-Factor Authentication", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            when {
                state.isLoading -> AccessibleCircularProgressIndicator()
                state.error != null && state.status == null -> {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { twoFactorVm.loadStatus(vaultId) }) { Text("Retry") }
                }
                state.status != null -> {
                    val twoFaStatus = state.status!!
                    if (twoFaStatus.enabled) {
                        Text("Status: Enabled (${twoFaStatus.method?.name?.uppercase() ?: "—"})")
                        Text("Verified: ${if (twoFaStatus.verified) "Yes" else "No"}")
                        Spacer(Modifier.height(12.dp))
                        if (!twoFaStatus.verified) {
                            OutlinedButton(onClick = { showVerify = true },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Verify Now")
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        // #225: Switch method without disabling first.
                        if (twoFaStatus.availableMethods.size > 1) {
                            OutlinedButton(
                                onClick = { showSwitch = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Switch Method") }
                            Spacer(Modifier.height(8.dp))
                        }
                        biometricError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                        }
                        // #120: Biometric gate — prompt before the destructive disable call.
                        Button(
                            onClick = {
                                biometricError = null
                                BiometricHelper(context as androidx.fragment.app.FragmentActivity)
                                    .authenticate(
                                        title = "Confirm Disable 2FA",
                                        subtitle = "Biometric or PIN required to disable two-factor authentication",
                                        onSuccess = {
                                            twoFactorVm.disable2FAAfterBiometric(vaultId)
                                        },
                                        onError = { err -> biometricError = err }
                                    )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isLoading
                        ) { Text("Disable 2FA") }
                    } else {
                        Text("Status: Disabled")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showSetup = true },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Enable 2FA")
                        }
                    }
                }
                else -> {
                    Text("Loading 2FA status…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

package com.ethosprotocol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethosprotocol.services.BiometricHelper
import com.ethosprotocol.services.VaultDeepLinkAction
import com.ethosprotocol.ui.VaultViewModel

// MARK: - Vault Deep Link Screen

@Composable
fun VaultDeepLinkScreen(
    vaultId: String,
    actionPath: String,
    onDone: () -> Unit,
    onDeposit: (vaultId: String) -> Unit = {},
    onWithdraw: (vaultId: String) -> Unit = {},
    vm: VaultViewModel = hiltViewModel()
) {
    val action = VaultDeepLinkAction.fromPathSegment(actionPath)
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showBeneficiaryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }

    val vault = state.vaults.find { it.id == vaultId }
    val (title, description) = when (action) {
        VaultDeepLinkAction.CHECK_IN -> "Check In" to "Confirm check-in for vault ${vaultId.take(12)}…"
        VaultDeepLinkAction.WITHDRAW -> "Withdraw" to "Withdraw funds from vault ${vaultId.take(12)}…"
        VaultDeepLinkAction.VIEW_DETAILS -> "Vault Details" to "View details for vault ${vaultId.take(12)}…"
        VaultDeepLinkAction.MANAGE_BENEFICIARY -> "Manage Beneficiary" to "Update beneficiary for vault ${vaultId.take(12)}…"
        null -> "Vault Link" to "Unrecognised vault action."
    }

    // VIEW_DETAILS early-exit case with full vault card
    if (action == VaultDeepLinkAction.VIEW_DETAILS && vault != null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            VaultCard(vault = vault, onClick = {}, onCheckIn = {})
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
        return
    }

    // Determine display error:
    // - Show "Vault not found" ONLY if loading has completed and vault is still null
    // - Do NOT show "not found" while still loading (prevents false flash)
    val displayError = error ?: if (!state.isLoading && vault == null && action == VaultDeepLinkAction.VIEW_DETAILS) {
        "Vault not found"
    } else {
        null
    }

    val actionLabel = when (action) {
        VaultDeepLinkAction.CHECK_IN -> "Check In"
        VaultDeepLinkAction.WITHDRAW -> "Withdraw"
        VaultDeepLinkAction.MANAGE_BENEFICIARY -> "Manage Beneficiary"
        else -> title
    }

    VaultDeepLinkScreenContent(
        title = title,
        description = description,
        error = displayError,
        actionLabel = actionLabel,
        isProcessing = isProcessing,
        actionEnabled = !isProcessing && (action != VaultDeepLinkAction.CHECK_IN || vault != null),
        isLoading = state.isLoading,
        onAction = {
            when (action) {
                VaultDeepLinkAction.CHECK_IN -> {
                    if (vault == null) {
                        error = "Vault not found"
                        return@VaultDeepLinkScreenContent
                    }
                    isProcessing = true
                    error = null
                    BiometricHelper(context as androidx.fragment.app.FragmentActivity).authenticate(
                        title = "Confirm Check-In",
                        subtitle = "Vault ${vault.id.take(12)}…",
                        onSuccess = {
                            vm.checkIn(vault.id)
                            isProcessing = false
                            onDone()
                        },
                        onError = { err ->
                            error = err
                            isProcessing = false
                        }
                    )
                }
                VaultDeepLinkAction.WITHDRAW -> {
                    if (vault == null) error = "Vault not found"
                    else onWithdraw(vault.id)
                }
                VaultDeepLinkAction.MANAGE_BENEFICIARY -> {
                    error = "Beneficiary management is not yet available in the mobile app."
                }
                else -> Unit
            }
        },
        onDone = onDone
    )

    if (showBeneficiaryDialog && vault != null) {
        ManageBeneficiaryDialog(
            currentBeneficiary = vault.beneficiary,
            onSubmit = { newBeneficiary ->
                showBeneficiaryDialog = false
                isProcessing = true
                error = null
                BiometricHelper(context as androidx.fragment.app.FragmentActivity).authenticate(
                    title = "Confirm Beneficiary Change",
                    subtitle = "Vault ${vault.id.take(12)}… beneficiary will change to ${newBeneficiary.take(12)}…",
                    onSuccess = {
                        vm.updateBeneficiary(vault.id, newBeneficiary)
                        isProcessing = false
                        onDone()
                    },
                    onError = { err ->
                        error = err
                        isProcessing = false
                    }
                )
            },
            onDismiss = { showBeneficiaryDialog = false }
        )
    }

}

/**
 * Stateless content layer for [VaultDeepLinkScreen].
 * Extracted so Paparazzi snapshot tests can render deep-link action screens
 * on the JVM without a ViewModel, BiometricHelper, or Activity context.
 */
@Composable
fun VaultDeepLinkScreenContent(
    title: String,
    description: String,
    error: String?,
    actionLabel: String,
    isProcessing: Boolean,
    actionEnabled: Boolean,
    isLoading: Boolean = false,
    onAction: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isLoading) {
            Spacer(Modifier.height(16.dp))
            AccessibleCircularProgressIndicator(
                Modifier.align(Alignment.CenterHorizontally).testTag("loading")
            )
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAction,
            modifier = Modifier.fillMaxWidth(),
            enabled = actionEnabled
        ) {
            Text(if (isProcessing) "Processing…" else actionLabel)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun ManageBeneficiaryDialog(
    currentBeneficiary: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var beneficiary by remember { mutableStateOf(currentBeneficiary) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Beneficiary") },
        text = {
            Column {
                Text(
                    "This vault's funds are released to this address if it is never checked into again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = beneficiary, onValueChange = { beneficiary = it },
                    label = { Text("Beneficiary address") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(beneficiary) },
                enabled = beneficiary.isNotBlank() && beneficiary != currentBeneficiary
            ) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

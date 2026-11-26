package com.ethosprotocol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethosprotocol.R
import com.ethosprotocol.ui.AcceptanceViewModel
import com.ethosprotocol.ui.VaultViewModel

// MARK: - Beneficiary Acceptance Screen

@Composable
fun BeneficiaryAcceptanceScreen(
    vaultId: String,
    // token: parsed from the /accept deep-link URL; required by the server (#109).
    token: String,
    onAccepted: () -> Unit,
    onDecline: () -> Unit,
    vm: AcceptanceViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isAccepted) {
        if (state.isAccepted) onAccepted()
    }

    BeneficiaryAcceptanceScreenContent(
        vaultId = vaultId,
        isLoading = state.isLoading,
        error = state.error,
        onAccept = { vm.accept(vaultId, token) },
        onDecline = onDecline
    )
}

/**
 * Stateless content layer extracted so Paparazzi screenshot tests can render it
 * on the JVM without a Hilt DI graph.
 */
@Composable
fun BeneficiaryAcceptanceScreenContent(
    vaultId: String,
    isLoading: Boolean,
    error: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = "Secure beneficiary acceptance",
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.beneficiary_acceptance_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "You have been named as the beneficiary for the following vault:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = vaultId,
            onValueChange = {},
            label = { Text(stringResource(R.string.beneficiary_vault_id_label)) },
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.action_accept))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            Text(stringResource(R.string.action_decline))
        }
    }
}

// MARK: - Manage Beneficiary Screen

@Composable
fun ManageBeneficiaryScreen(
    vault: com.ethosprotocol.models.Vault,
    onDone: () -> Unit,
    vm: VaultViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var newBeneficiary by remember { mutableStateOf("") }
    var showConfirmation by remember { mutableStateOf(false) }

    // The server rejects an address that is empty or unchanged. Mirror the same
    // validation used by iOS BeneficiaryUpdate.isValidNewBeneficiary().
    val isAddressValid = newBeneficiary.trim().isNotEmpty() && newBeneficiary.trim() != vault.beneficiary

    LaunchedEffect(state.beneficiaryUpdated) {
        if (state.beneficiaryUpdated) {
            vm.clearBeneficiaryUpdated()
            onDone()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Person, contentDescription = null,
            modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.manage_beneficiary_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.manage_beneficiary_current_label), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(vault.beneficiary,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(16.dp))

        if (showConfirmation) {
            // Confirmation step — mirrors iOS ManageBeneficiaryView.confirmationContent
            Text(stringResource(R.string.manage_beneficiary_change_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.manage_beneficiary_from_label), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(vault.beneficiary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.manage_beneficiary_to_label), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(newBeneficiary.trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.updateBeneficiary(vault.id, newBeneficiary.trim()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading
            ) {
                if (state.isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.manage_beneficiary_confirm_change))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showConfirmation = false },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading
            ) { Text(stringResource(R.string.action_back)) }
        } else {
            // Input step
            OutlinedTextField(
                value = newBeneficiary,
                onValueChange = { newBeneficiary = it },
                label = { Text(stringResource(R.string.manage_beneficiary_new_address_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = newBeneficiary.isNotEmpty() && !isAddressValid,
                supportingText = if (newBeneficiary.isNotEmpty() && !isAddressValid) {
                    { Text(stringResource(R.string.manage_beneficiary_error_empty)) }
                } else null
            )
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { showConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = isAddressValid
            ) { Text("Continue") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cancel") }
        }
    }
}

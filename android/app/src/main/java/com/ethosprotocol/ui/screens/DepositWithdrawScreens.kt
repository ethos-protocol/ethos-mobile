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
import com.ethosprotocol.services.BiometricHelper

// MARK: - Deposit Screen

/**
 * The screen is reached either from the vault detail action list or from the
 * deep-link action router (deposit action). It navigates back via [onDone] on
 * success.
 */
@Composable
fun DepositScreen(
    vaultId: String,
    vm: com.ethosprotocol.ui.DepositViewModel = hiltViewModel(),
    onDone: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var amountInput by remember { mutableStateOf("") }

    // Navigate away as soon as the deposit succeeds
    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) onDone()
    }

    DepositScreenContent(
        vaultId = vaultId,
        amountInput = amountInput,
        isLoading = state.isLoading,
        error = state.error,
        onAmountChange = { amountInput = it },
        onDeposit = { vm.deposit(vaultId, amountInput) },
        onDone = onDone
    )
}

/**
 * Stateless content layer extracted so Paparazzi screenshot tests can render it
 * on the JVM without a Hilt DI graph.
 */
@Composable
fun DepositScreenContent(
    vaultId: String,
    amountInput: String,
    isLoading: Boolean,
    error: String?,
    onAmountChange: (String) -> Unit,
    onDeposit: () -> Unit,
    onDone: () -> Unit
) {
    val isAmountValid = amountInput.toDoubleOrNull()?.let { it > 0 } == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Deposit") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Vault: ${vaultId.take(12)}…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = amountInput,
                onValueChange = onAmountChange,
                label = { Text("Amount (XLM)") },
                placeholder = { Text("0.0000000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                ),
                isError = amountInput.isNotEmpty() && !isAmountValid
            )

            if (amountInput.isNotEmpty() && !isAmountValid) {
                Text(
                    "Enter a valid positive XLM amount.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = onDeposit,
                modifier = Modifier.fillMaxWidth(),
                enabled = isAmountValid && !isLoading
            ) {
                if (isLoading) {
                    AccessibleCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Deposit")
                }
            }
        }
    }
}

// MARK: - Withdraw Screen

/**
 * The screen enforces:
 *  - Amount must be a positive number in XLM.
 *  - Amount must not exceed the vault's current balance (client-side guard;
 *    the server enforces this too).
 *  - Biometric auth is required before the API call is dispatched.
 *
 * Navigates back via [onDone] on success.
 */
@Composable
fun WithdrawScreen(
    vaultId: String,
    vaultBalanceStroops: Long,
    vm: com.ethosprotocol.ui.WithdrawViewModel = hiltViewModel(),
    onDone: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var amountInput by remember { mutableStateOf("") }
    var biometricError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) onDone()
    }

    // Formatted balance shown to the user (same formula as Vault.formattedBalance)
    val availableBalance = remember(vaultBalanceStroops) {
        "%.7f XLM".format(vaultBalanceStroops / 10_000_000.0)
    }

    WithdrawScreenContent(
        vaultId = vaultId,
        availableBalance = availableBalance,
        amountInput = amountInput,
        isLoading = state.isLoading,
        error = state.error ?: biometricError,
        onAmountChange = { amountInput = it },
        onWithdraw = {
            // Biometric gate is required before dispatching a withdrawal.
            biometricError = null
            BiometricHelper(context as androidx.fragment.app.FragmentActivity).authenticate(
                title = "Confirm Withdrawal",
                subtitle = "Withdraw $amountInput XLM from vault ${vaultId.take(12)}…",
                onSuccess = { vm.withdraw(vaultId, amountInput, vaultBalanceStroops) },
                onError = { err -> biometricError = err }
            )
        },
        onDone = onDone
    )
}

/**
 * Stateless content layer extracted so Paparazzi screenshot tests can render it
 * on the JVM without a Hilt DI graph.
 */
@Composable
fun WithdrawScreenContent(
    vaultId: String,
    availableBalance: String,
    amountInput: String,
    isLoading: Boolean,
    error: String?,
    onAmountChange: (String) -> Unit,
    onWithdraw: () -> Unit,
    onDone: () -> Unit
) {
    val amountDouble = amountInput.toDoubleOrNull()
    val isAmountValid = amountDouble != null && amountDouble > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Withdraw") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Vault: ${vaultId.take(12)}…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                "Available: $availableBalance",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = amountInput,
                onValueChange = onAmountChange,
                label = { Text("Amount (XLM)") },
                placeholder = { Text("0.0000000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                ),
                isError = amountInput.isNotEmpty() && !isAmountValid
            )

            if (amountInput.isNotEmpty() && !isAmountValid) {
                Text(
                    "Enter a valid positive XLM amount.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = onWithdraw,
                modifier = Modifier.fillMaxWidth(),
                enabled = isAmountValid && !isLoading
            ) {
                if (isLoading) {
                    AccessibleCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Withdraw")
                }
            }

            Text(
                "Biometric confirmation is required before the withdrawal is submitted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


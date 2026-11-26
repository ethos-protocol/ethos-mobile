package com.ethosprotocol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethosprotocol.models.TwoFactorMethod
import com.ethosprotocol.ui.TwoFactorViewModel

// MARK: - 2FA Screens

@Composable
fun TwoFactorSetupScreen(
    vaultId: String,
    onComplete: () -> Unit,
    onDismiss: () -> Unit,
    /** #227: Only show methods the server reports as available for this account. */
    availableMethods: List<TwoFactorMethod> = TwoFactorMethod.values().toList()
) {
    val vm: TwoFactorViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // #227: Default to first available method; fall back to totp if list is somehow empty.
    var selectedMethod by remember {
        mutableStateOf(
            if (availableMethods.contains(TwoFactorMethod.totp)) TwoFactorMethod.totp
            else availableMethods.firstOrNull() ?: TwoFactorMethod.totp
        )
    }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    // #224: Show backup codes screen immediately after initial TOTP setup verification.
    if (state.showBackupCodes) {
        BackupCodesScreen(
            codes = state.backupCodes,
            isLoading = state.isLoading,
            onDone = { vm.dismissBackupCodes(); onComplete() }
        )
        return
    }

    if (state.setupResponse != null) {
        TwoFactorVerifyScreen(
            vaultId = vaultId,
            method = selectedMethod,
            provisioningUri = state.setupResponse?.provisioningUri,
            onVerified = { onComplete() },
            onDismiss = onDismiss,
            vm = vm
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable 2FA") },
        text = {
            Column {
                Text("Authentication Method", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                // #227: Only render available methods.
                availableMethods.forEach { method ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedMethod == method,
                            onClick = { selectedMethod = method }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (method) {
                                TwoFactorMethod.totp -> "Authenticator App (TOTP)"
                                TwoFactorMethod.sms -> "SMS Code"
                                TwoFactorMethod.email -> "Email Code"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (selectedMethod == TwoFactorMethod.sms) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = phone, onValueChange = { phone = it },
                        label = { Text("Phone number") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                if (selectedMethod == TwoFactorMethod.email) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = email, onValueChange = { email = it },
                        label = { Text("Email address") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.enable2FA(vaultId, selectedMethod, phone, email)
                },
                enabled = !state.isLoading && when (selectedMethod) {
                    TwoFactorMethod.totp -> true
                    TwoFactorMethod.sms -> phone.isNotBlank()
                    TwoFactorMethod.email -> email.isNotBlank()
                }
            ) {
                if (state.isLoading) AccessibleCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Continue")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TwoFactorVerifyScreen(
    vaultId: String,
    method: TwoFactorMethod,
    provisioningUri: String?,
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
    vm: TwoFactorViewModel
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var otp by remember { mutableStateOf("") }
    val isInitialSetup = provisioningUri != null
    // #226: "Remember this device" opt-in.
    var trustDevice by remember { mutableStateOf(false) }

    LaunchedEffect(state.verified) {
        if (state.verified) onVerified()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Decorative: the method-specific instructions below ("Scan the URI…", "A verification
        // code has been sent to your phone/email") already convey which method is active.
        Icon(
            when (method) {
                TwoFactorMethod.totp -> Icons.Default.Lock
                TwoFactorMethod.sms -> Icons.Default.Email
                TwoFactorMethod.email -> Icons.Default.Email
            },
            contentDescription = null, modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        // Title distinguishes initial setup from a subsequent re-verification so
        // the user is not confused about why they are not receiving a "sent" code.
        val titleText = when {
            method == TwoFactorMethod.totp && isInitialSetup -> "Verify Setup"
            method == TwoFactorMethod.totp -> "Re-verify Authenticator"
            else -> "Verify Setup"
        }
        Text(titleText, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        when {
            method == TwoFactorMethod.totp && isInitialSetup -> {
                // Initial TOTP setup: the server returned a provisioning URI — show it.
                Text(
                    "Scan this URI in your authenticator app:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    provisioningUri ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            method == TwoFactorMethod.totp -> {
                // Re-verification: no provisioning data — the user must open their
                // authenticator app and enter the current code. Never say "sent".
                Text(
                    "Enter the 6-digit code from your authenticator app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            method == TwoFactorMethod.sms -> Text(
                "A verification code has been sent to your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Text(
                "A verification code has been sent to your email.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = otp, onValueChange = { otp = it },
            label = { Text("6-digit code") }, singleLine = true,
            modifier = Modifier.width(200.dp),
            textStyle = MaterialTheme.typography.headlineSmall,
            enabled = !state.isOtpBlocked
        )

        // #119: Surface cooldown / failure count in the UI.
        Spacer(Modifier.height(8.dp))
        when {
            state.isOtpBlocked -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timer, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Too many attempts — wait ${state.otpCooldownSeconds}s",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            state.otpFailureCount > 0 -> {
                Text(
                    "${state.otpFailureCount} failed attempt${if (state.otpFailureCount == 1) "" else "s"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            else -> Unit
        }

        // #226: Trust-device opt-in row.
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = trustDevice,
                onCheckedChange = { trustDevice = it }
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Remember this device", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Skip 2FA on this device for 30 days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.verify2FA(vaultId, otp, trustDevice) },
            modifier = Modifier.fillMaxWidth(),
            enabled = otp.length == 6 && !state.isLoading && !state.isOtpBlocked
        ) {
            if (state.isLoading) AccessibleCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Verify")
        }
    }
}

// MARK: - #224 Backup Codes Screen

/**
 * Displays the one-time backup codes generated after initial TOTP setup.
 * Codes are shown once and cannot be retrieved again — the server stores only hashes.
 */
@Composable
fun BackupCodesScreen(
    codes: List<String>,
    isLoading: Boolean,
    onDone: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Backup Codes") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.Key,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("Save Your Backup Codes", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Store these codes somewhere safe. Each code can be used once to sign in if you " +
                "lose access to your authenticator app. They will not be shown again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else {
                androidx.compose.foundation.lazy.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(codes.size) { index ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                codes[index],
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Text("I've Saved My Codes")
            }
        }
    }
}

// MARK: - #225 Switch 2FA Method Screen

/**
 * Initiates a 2FA method switch without a gap in protection.
 * Sets up the new method (pending) → user verifies it → old method is torn down atomically.
 */
@Composable
fun TwoFactorSwitchScreen(
    vaultId: String,
    currentMethod: TwoFactorMethod,
    availableMethods: List<TwoFactorMethod>,
    onComplete: () -> Unit,
    onDismiss: () -> Unit,
    vm: TwoFactorViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Methods the user can switch *to* — exclude the currently active one.
    val switchableMethods = availableMethods.filter { it != currentMethod }
    var selectedMethod by remember {
        mutableStateOf(switchableMethods.firstOrNull() ?: TwoFactorMethod.totp)
    }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    // Once the switch is initiated and the new method verified, complete.
    LaunchedEffect(state.verified) {
        if (state.verified) { vm.clearSwitchResponse(); onComplete() }
    }

    if (state.switchResponse != null) {
        // New method is pending — verify it to complete the switch.
        TwoFactorVerifyScreen(
            vaultId = vaultId,
            method = selectedMethod,
            provisioningUri = state.switchResponse?.provisioningUri,
            onVerified = onComplete,
            onDismiss = onDismiss,
            vm = vm
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch 2FA Method") },
        text = {
            Column {
                Text(
                    "Current method: ${currentMethod.name.uppercase()}. " +
                    "Choose a new method. Your account stays protected until you verify the new one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                switchableMethods.forEach { method ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedMethod == method,
                            onClick = { selectedMethod = method }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (method) {
                                TwoFactorMethod.totp -> "Authenticator App (TOTP)"
                                TwoFactorMethod.sms -> "SMS Code"
                                TwoFactorMethod.email -> "Email Code"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (selectedMethod == TwoFactorMethod.sms) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = phone, onValueChange = { phone = it },
                        label = { Text("Phone number") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                if (selectedMethod == TwoFactorMethod.email) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = email, onValueChange = { email = it },
                        label = { Text("Email address") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.switch2FAMethod(vaultId, selectedMethod, phone.ifBlank { null }, email.ifBlank { null })
                },
                enabled = !state.isLoading && when (selectedMethod) {
                    TwoFactorMethod.totp -> true
                    TwoFactorMethod.sms -> phone.isNotBlank()
                    TwoFactorMethod.email -> email.isNotBlank()
                }
            ) {
                if (state.isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Continue")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

package com.ethosprotocol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethosprotocol.R
import com.ethosprotocol.services.UsernameValidator
import com.ethosprotocol.ui.AuthUiState
import com.ethosprotocol.ui.AuthViewModel
import com.ethosprotocol.utils.TrackScreen

// MARK: - Auth Screen

@Composable
fun AuthScreen(vm: AuthViewModel = hiltViewModel()) {
    TrackScreen("Auth")
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as android.app.Activity
    var showRegister by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }

    if (showRegister) {
        RegisterSheet(
            onRegister = { username -> vm.register(activity, username); showRegister = false },
            onDismiss = { showRegister = false }
        )
    }

    AuthScreenContent(
        isLoading = state.isLoading,
        error = state.error,
        cooldownRemainingSeconds = state.cooldownRemainingSeconds,
        onSignIn = { vm.signIn(activity) },
        onRegister = { showRegister = true }
    )
}

/**
 * Stateless content layer extracted so Paparazzi screenshot tests can render it
 * on the JVM without an Activity context or Hilt DI graph.
 */
@Composable
fun AuthScreenContent(
    isLoading: Boolean,
    error: String?,
    cooldownRemainingSeconds: Int = 0,
    onSignIn: () -> Unit,
    onRegister: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).focusRequester(focusRequester),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, contentDescription = "Secure sign-in",
            modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.app_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        if (cooldownRemainingSeconds > 0) {
            Text(
                stringResource(R.string.auth_too_many_attempts, cooldownRemainingSeconds),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && cooldownRemainingSeconds == 0
        ) {
            if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else { Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sign_in_with_passkey)) }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRegister) { Text(stringResource(R.string.create_account)) }
    }
}

@Composable
private fun RegisterSheet(onRegister: (String) -> Unit, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    val trimmedUsername = username.trim()
    val isValid = UsernameValidator.isValid(trimmedUsername)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.register_title)) },
        text = {
            Column {
                OutlinedTextField(value = username, onValueChange = { username = it },
                    label = { Text(stringResource(R.string.register_username_label)) }, singleLine = true)
                if (username.isNotBlank() && !isValid) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.register_username_rules, UsernameValidator.MIN_LENGTH, UsernameValidator.MAX_LENGTH),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRegister(trimmedUsername) }, enabled = isValid) { Text(stringResource(R.string.register_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun RecoverySheet(
    state: AuthUiState,
    onSendCode: (String) -> Unit,
    onFinish: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    val codeSent = state.recoveryToken != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recover_title)) },
        text = {
            Column {
                if (!codeSent) {
                    Text(
                        stringResource(R.string.recover_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = username, onValueChange = { username = it },
                        label = { Text(stringResource(R.string.register_username_label)) }, singleLine = true,
                        enabled = !state.isLoading, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(
                        stringResource(R.string.recover_confirmation_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (!codeSent) {
                TextButton(
                    onClick = { onSendCode(username) },
                    enabled = username.isNotBlank() && !state.isLoading
                ) { Text(stringResource(R.string.recover_send_code)) }
            } else {
                TextButton(onClick = { onFinish(username) }, enabled = !state.isLoading) { Text(stringResource(R.string.action_continue)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

package com.ethosprotocol.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ethosprotocol.R
import com.ethosprotocol.models.DestructiveConfirmation
import com.ethosprotocol.services.ReduceMotionChecker

// MARK: - Accessibility Helpers

/**
 * Renders a CircularProgressIndicator that respects the user's reduce-motion accessibility setting.
 * When reduce motion is enabled, shows an empty Box instead of the animated spinner.
 */
@Composable
internal fun AccessibleCircularProgressIndicator(modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp) {
    val context = LocalContext.current
    val shouldReduceMotion = ReduceMotionChecker.isReduceMotionEnabled(context)

    if (shouldReduceMotion) {
        Box(modifier = modifier)
    } else {
        CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth)
    }
}

// MARK: - Destructive Confirmation (#220)

/**
 * Shared confirmation UI for any irreversible, destructive vault action
 * (delete, archive, …) on either platform. The confirm button stays disabled
 * until the user types [requiredText] (typically the vault's own name/ID)
 * exactly — a plain Yes/No tap is not enough given the financial and
 * beneficiary implications of getting this wrong. [onConfirm] is only ever
 * invoked once [DestructiveConfirmation.isConfirmed] is true.
 */
@Composable
fun DestructiveConfirmationDialog(
    title: String,
    message: String,
    requiredText: String,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var enteredText by remember { mutableStateOf("") }
    val confirmation = DestructiveConfirmation(requiredText, enteredText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Type \"$requiredText\" to confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = enteredText,
                    onValueChange = { enteredText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                // Guarded via confirmIfMatched, not just `enabled` below — `enabled`
                // only stops a tap from reaching this closure through the button's own
                // click handler; it is not what makes this safe.
                onClick = { confirmation.confirmIfMatched(onConfirm) },
                enabled = confirmation.isConfirmed,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

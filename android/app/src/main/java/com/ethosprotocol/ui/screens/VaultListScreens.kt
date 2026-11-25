package com.ethosprotocol.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethosprotocol.R
import com.ethosprotocol.models.StellarAddress
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.services.BiometricHelper
import com.ethosprotocol.ui.VaultViewModel
import com.ethosprotocol.utils.TrackScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// MARK: - Vault List Screen

@Composable
fun VaultListScreen(
    onVaultClick: (String) -> Unit,
    onDebugLogClick: (() -> Unit)? = null,
    vm: VaultViewModel = hiltViewModel()
) {
    TrackScreen("VaultList")
    val state by vm.state.collectAsStateWithLifecycle()
    val expiringState by vm.expiringVaultsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    var pendingCheckIn by remember { mutableStateOf<Vault?>(null) }
    var biometricError by remember { mutableStateOf<String?>(null) }

    // #118 / #485: Non-blocking root warning. The check itself (su-binary/package/mount-point
    // scans, see IntegrityChecker) is file-I/O-bound, so it's deferred off the composition's
    // initial state and run on Dispatchers.Default instead of blocking the first frame of this
    // screen — the list and its data load are not gated on it.
    var showRootWarning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showRootWarning = withContext(Dispatchers.Default) {
            com.ethosprotocol.services.IntegrityChecker(context).isRooted
        }
    }

    LaunchedEffect(Unit) { vm.load() }

    // #118: Non-blocking root warning dialog.
    if (showRootWarning) {
        AlertDialog(
            onDismissRequest = { showRootWarning = false },
            title = { Text(stringResource(R.string.security_warning_title)) },
            text = {
                Text(
                    stringResource(R.string.security_warning_message)
                )
            },
            confirmButton = {
                TextButton(onClick = { showRootWarning = false }) { Text(stringResource(R.string.security_warning_acknowledge)) }
            }
        )
    }

    if (showCreate) {
        CreateVaultDialog(
            onCreate = { ben, days -> vm.createVault(ben, days); showCreate = false },
            onDismiss = { showCreate = false }
        )
    }

    pendingCheckIn?.let { vault ->
        CheckInConfirmationDialog(
            vault = vault,
            onConfirm = {
                pendingCheckIn = null
                BiometricHelper(context as androidx.fragment.app.FragmentActivity).authenticate(
                    title = stringResource(R.string.checkin_title),
                    subtitle = "Vault ${vault.id.take(12)}… will extend by ${formatInterval(vault.checkInInterval)}",
                    onSuccess = { vm.checkIn(vault.id) },
                    onError = { err -> biometricError = err },
                )
            },
            onDismiss = { pendingCheckIn = null },
        )
    }


    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.vault_list_title)) }, actions = {
                // #235: debug/QA-only entry to the notification delivery log — never
                // shown in a release build (onDebugLogClick is only non-null when
                // MainActivity's nav graph wires it in under BuildConfig.DEBUG).
                onDebugLogClick?.let { onClick ->
                    IconButton(onClick = onClick) { Icon(Icons.Default.BugReport, stringResource(R.string.notification_log_title)) }
                }
                IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, stringResource(R.string.vault_create_button)) }
            })
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading && state.vaults.isEmpty() ->
                    AccessibleCircularProgressIndicator(Modifier.align(Alignment.Center))
                state.vaults.isEmpty() ->
                    Text(stringResource(R.string.vault_list_empty),
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    // Ties the pull gesture to the same VaultViewModel.load() used for the initial
                    // fetch, so state.isLoading naturally drives the pull indicator too.
                    val refreshState = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = state.isLoading,
                        onRefresh = { vm.load() },
                        state = refreshState,
                        modifier = Modifier.fillMaxSize().testTag("vaultListPullToRefresh"),
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                isRefreshing = state.isLoading,
                                state = refreshState,
                                color = MaterialTheme.colorScheme.tertiary,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                    ) {
                        LazyColumn(
                            contentPadding = WindowInsets.navigationBars.asPaddingValues()
                        ) {
                            if (state.isOffline) item {
                                OfflineBanner()
                            }
                            if (expiringState.expiredVaults.isNotEmpty() && !expiringState.isDismissed) {
                                item {
                                    ExpiringVaultsBanner(
                                        vaults = expiringState.expiredVaults,
                                        onDismiss = { vm.dismissExpiringVaultsBanner() },
                                        onCheckIn = { vault -> pendingCheckIn = vault }
                                    )
                                }
                            }
                            val errorMsg = biometricError ?: state.error
                            errorMsg?.let { err ->
                                item {
                                    Text(err, color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(16.dp))
                                }
                            }
                            items(state.vaults, key = { it.id }) { vault ->
                                VaultCard(
                                    vault = vault,
                                    onClick = { onVaultClick(vault.id) },
                                    onCheckIn = { pendingCheckIn = vault },
                                )
                            }
                            if (state.hasMore) item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (state.isLoadingMore) {
                                        AccessibleCircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        OutlinedButton(onClick = { vm.loadMore() }) { Text(stringResource(R.string.vault_list_load_more)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatInterval(seconds: Long): String {
    val days = seconds / 86_400
    return if (days > 0) "$days day${if (days == 1L) "" else "s"}" else "${seconds / 3_600}h"
}

@Composable
private fun CheckInConfirmationDialog(vault: Vault, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkin_title)) },
        text = {
            Column {
                Text(stringResource(R.string.checkin_vault_id, vault.id.take(12)),
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.checkin_ttl_extension, formatInterval(vault.checkInInterval)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.checkin_requires_auth),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun OfflineBanner(cachedAt: Long? = null) {
    val message = if (cachedAt != null) {
        "Offline — showing cached data (as of ${formatCacheAge(cachedAt)} ago)"
    } else {
        "Offline — showing cached data"
    }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        // mergeDescendants groups the icon + label into a single TalkBack stop instead of two,
        // so giving the icon a description adds context without a duplicate announcement.
        Row(
            Modifier.fillMaxWidth().padding(12.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WifiOff, contentDescription = "Offline",
                tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(message, color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ExpiringVaultsBanner(
    vaults: List<Vault>,
    onDismiss: () -> Unit,
    onCheckIn: (Vault) -> Unit
) {
    val summary = if (vaults.size == 1) {
        "1 vault expires in less than 24 hours"
    } else {
        "${vaults.size} vaults expire in less than 24 hours"
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, contentDescription = "Expiring Soon",
                    tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(summary, color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Dismiss", fontSize = MaterialTheme.typography.labelSmall.fontSize)
                }
                if (vaults.size == 1) {
                    Button(onClick = { onCheckIn(vaults[0]) }, modifier = Modifier.weight(1f)) {
                        Text("Check In", fontSize = MaterialTheme.typography.labelSmall.fontSize)
                    }
                }
            }
        }
    }
}

private fun formatCacheAge(cachedAt: Long): String {
    val elapsedSeconds = ((System.currentTimeMillis() - cachedAt) / 1000).coerceAtLeast(0)
    val minutes = elapsedSeconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}d"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "a few seconds"
    }
}

@Composable
fun VaultCard(
    vault: Vault,
    onClick: () -> Unit,
    onCheckIn: () -> Unit,
    onDeposit: () -> Unit = {},
    onWithdraw: () -> Unit = {}
) {
    val statusColor = vault.status.statusColor()
    val animatedStatusColor by animateColorAsState(statusColor, label = "vaultStatusColor")
    val containerColor by animateColorAsState(
        when (vault.status) {
            VaultStatus.expired -> MaterialTheme.colorScheme.errorContainer
            VaultStatus.released -> MaterialTheme.colorScheme.secondaryContainer
            VaultStatus.paused -> MaterialTheme.colorScheme.surfaceVariant
            VaultStatus.active -> MaterialTheme.colorScheme.surface
        },
        label = "vaultStatusContainer"
    )
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, animatedStatusColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // At the largest font scale a full-length id + chip in one row will clip rather than
            // wrap the layout; ellipsize the id (already truncated to 12 chars) so the chip stays visible.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vault.id.take(12) + "…", style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                StatusChip(vault.status)
            }
            Spacer(Modifier.height(4.dp))
            Text(vault.formattedBalance, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            vault.ttlRemaining?.let { ttl ->
                Spacer(Modifier.height(4.dp))
                VaultCountdownText(ttl)
            }
            if (vault.isExpiringSoon) {
                Spacer(Modifier.height(4.dp))
                // mergeDescendants groups the icon + label into a single TalkBack stop instead of two.
                Row(
                    Modifier.semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.checkin_expiring_soon), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (vault.status == com.ethosprotocol.models.VaultStatus.active) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCheckIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.checkin_button))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDeposit, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.vault_deposit))
                    }
                    OutlinedButton(onClick = onWithdraw, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.vault_withdraw))
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultCountdownText(initialTtlSeconds: Long) {
    var remainingSeconds by remember(initialTtlSeconds) { mutableLongStateOf(initialTtlSeconds.coerceAtLeast(0L)) }
    LaunchedEffect(initialTtlSeconds) {
        remainingSeconds = initialTtlSeconds.coerceAtLeast(0L)
        while (remainingSeconds > 0L) {
            delay(1_000)
            remainingSeconds = (remainingSeconds - 1L).coerceAtLeast(0L)
        }
    }
    val urgencyAlpha by animateFloatAsState(
        targetValue = if (remainingSeconds < 3_600L) 1f else 0.82f,
        label = "vaultCountdownAlpha"
    )
    val countdownColor = when {
        remainingSeconds == 0L -> MaterialTheme.colorScheme.error
        remainingSeconds < 3_600L -> MaterialTheme.colorScheme.error
        remainingSeconds < 86_400L -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = "Vault countdown",
            tint = countdownColor,
            modifier = Modifier.size(14.dp).alpha(urgencyAlpha)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "Unlocks in ${formatCountdown(remainingSeconds)}",
            color = countdownColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (remainingSeconds < 3_600L) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(urgencyAlpha)
        )
    }
}

private fun formatCountdown(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    val secs = seconds % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

@Composable
private fun VaultStatus.statusColor() = when (this) {
    VaultStatus.active -> MaterialTheme.colorScheme.primary
    VaultStatus.expired -> MaterialTheme.colorScheme.error
    VaultStatus.released -> MaterialTheme.colorScheme.secondary
    VaultStatus.paused -> MaterialTheme.colorScheme.outline
}

@Composable
private fun StatusChip(status: VaultStatus) {
    val label = when (status) {
        VaultStatus.active -> "Active"
        VaultStatus.expired -> "Expired"
        VaultStatus.released -> "Released"
        VaultStatus.paused -> "Paused"
    }
    val color = status.statusColor()
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = RoundedCornerShape(50),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.12f),
            labelColor = color
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.55f))
    )
}

@Composable
private fun CreateVaultDialog(onCreate: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var beneficiary by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(30f) }

    // Live validation using the shared StrKey spec (shared/stellar-validation-spec.md).
    val isBeneficiaryValid = StellarAddress.isValidPublicKey(beneficiary)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Vault") },
        text = {
            Column {
                OutlinedTextField(
                    value = beneficiary,
                    onValueChange = { beneficiary = it },
                    label = { Text("Beneficiary Stellar address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = beneficiary.isNotEmpty() && !isBeneficiaryValid,
                    supportingText = {
                        if (beneficiary.isNotEmpty() && !isBeneficiaryValid) {
                            Text("Enter a valid Stellar address (56 characters, starting with G).")
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                Text("Check-in interval: ${days.toInt()} days",
                    style = MaterialTheme.typography.bodySmall)
                Slider(value = days, onValueChange = { days = it }, valueRange = 1f..365f, steps = 363)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(beneficiary, days.toInt()) },
                enabled = isBeneficiaryValid) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

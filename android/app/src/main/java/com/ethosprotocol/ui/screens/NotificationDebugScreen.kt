package com.ethosprotocol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ethosprotocol.services.NotificationDeliveryLog
import com.ethosprotocol.ui.NotificationDebugViewModel

// MARK: - Notification Debug Log (#235)

/**
 * Debug-only screen listing the local notification delivery log, for
 * QA/support to answer "did this notification actually get scheduled,
 * delivered, or suppressed?" without backend log correlation. Never shows
 * anything beyond vault ID / event type / timestamp — see
 * [NotificationDeliveryLog]'s doc comment for what
 * is deliberately excluded. Only reachable when [com.ethosprotocol.BuildConfig.DEBUG].
 */
@Composable
fun NotificationDebugScreen(
    onBack: () -> Unit,
    vm: NotificationDebugViewModel = hiltViewModel()
) {
    val deliveryLog = vm.deliveryLog
    var events by remember {
        mutableStateOf(deliveryLog.recentEvents())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        deliveryLog.clear()
                        events = deliveryLog.recentEvents()
                    }) { Icon(Icons.Default.Delete, "Clear") }
                }
            )
        }
    ) { padding ->
        if (events.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No notification events logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(events) { event ->
                    Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                event.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(event.source.name.lowercase(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(event.eventType, style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(event.vaultId.take(12) + "…", style = MaterialTheme.typography.bodySmall)
                            Text(
                                com.ethosprotocol.utils.DateTimeFormatter.formatTime(event.timestampMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

package com.notimirror.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notimirror.ble.ConnectionState
import com.notimirror.data.AncsCategoryId
import com.notimirror.data.IPhoneNotification
import com.notimirror.viewmodel.MainViewModel
import com.notimirror.utils.AppNameFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: MainViewModel,
    onNavigatePairing: () -> Unit,
    onNavigateDebug: () -> Unit,
    onNavigateSettings: () -> Unit
) {
    val notifications by vm.notifications.collectAsState()
    val connectionState by vm.connectionState.collectAsState()
    val showBody by vm.showBody.collectAsState()
    val filteredApps by vm.filteredApps.collectAsState()

    val visibleNotifications = remember(notifications, filteredApps) {
        if (filteredApps.isEmpty()) notifications
        else notifications.filter { it.appIdentifier !in filteredApps }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NotiMirror") },
                actions = {
                    ConnectionBadge(connectionState, onNavigatePairing)
                    IconButton(onClick = onNavigateDebug) {
                        Icon(Icons.Default.BugReport, contentDescription = "Debug")
                    }
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (visibleNotifications.isEmpty()) {
                EmptyState(connectionState, onNavigatePairing)
            } else {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${visibleNotifications.size} notification${if (visibleNotifications.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        TextButton(onClick = { vm.clearNotifications() }) {
                            Text("Clear all")
                        }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleNotifications, key = { it.uid }) { notif ->
                            NotificationCard(notif, showBody)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionBadge(state: ConnectionState, onClick: () -> Unit) {
    val (icon, tint, label) = when (state) {
        is ConnectionState.ServiceDiscovered ->
            Triple(Icons.Default.BluetoothConnected, MaterialTheme.colorScheme.primary, "Connected")
        ConnectionState.Connecting, ConnectionState.Connected ->
            Triple(Icons.Default.Bluetooth, MaterialTheme.colorScheme.secondary, "Connecting")
        else ->
            Triple(Icons.Default.BluetoothDisabled, MaterialTheme.colorScheme.error, "Disconnected")
    }
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

@Composable
private fun EmptyState(connectionState: ConnectionState, onNavigatePairing: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val isConnected = connectionState is ConnectionState.ServiceDiscovered
        Icon(
            if (isConnected) Icons.Default.NotificationsNone else Icons.Default.BluetoothDisabled,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (isConnected) "No notifications yet" else "Not connected to iPhone",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        if (!isConnected) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onNavigatePairing) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Connect to iPhone")
            }
        }
    }
}

@Composable
private fun NotificationCard(notif: IPhoneNotification, showBody: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            CategoryIcon(notif.categoryId)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = AppNameFormatter.format(notif.appIdentifier),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (notif.date.isNotBlank()) {
                        Text(
                            text = formatAncsDate(notif.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    } else {
                        Text(
                            text = notif.receivedAt.atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm")),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                if (notif.title.isNotBlank()) {
                    Text(
                        text = notif.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (notif.subtitle.isNotBlank()) {
                    Text(
                        text = notif.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AnimatedVisibility(visible = showBody && notif.message.isNotBlank()) {
                    Text(
                        text = notif.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (notif.eventFlags.isImportant) {
                    Spacer(Modifier.height(4.dp))
                    Badge { Text("Important") }
                }
            }
        }
    }
}

@Composable
private fun CategoryIcon(category: AncsCategoryId) {
    val icon = when (category) {
        AncsCategoryId.IncomingCall, AncsCategoryId.MissedCall -> Icons.Default.Phone
        AncsCategoryId.Voicemail -> Icons.Default.Voicemail
        AncsCategoryId.Social -> Icons.Default.People
        AncsCategoryId.Email -> Icons.Default.Email
        AncsCategoryId.Schedule -> Icons.Default.CalendarToday
        AncsCategoryId.News -> Icons.Default.Article
        AncsCategoryId.HealthAndFitness -> Icons.Default.FitnessCenter
        AncsCategoryId.BusinessAndFinance -> Icons.Default.BusinessCenter
        AncsCategoryId.Location -> Icons.Default.LocationOn
        AncsCategoryId.Entertainment -> Icons.Default.MusicNote
        AncsCategoryId.Other -> Icons.Default.Notifications
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** ANCS date format: yyyyMMdd'T'HHmmss — convert to readable HH:mm */
private fun formatAncsDate(raw: String): String = try {
    val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    val dt = java.time.LocalDateTime.parse(raw, formatter)
    dt.format(DateTimeFormatter.ofPattern("HH:mm"))
} catch (_: Exception) { raw }

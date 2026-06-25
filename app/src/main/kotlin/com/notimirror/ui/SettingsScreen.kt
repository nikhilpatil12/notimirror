package com.notimirror.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notimirror.viewmodel.MainViewModel
import com.notimirror.utils.AppNameFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val showBody by vm.showBody.collectAsState()
    val keepScreenAwake by vm.keepScreenAwake.collectAsState()
    val autoReconnect by vm.autoReconnect.collectAsState()
    val showAndroidNotifications by vm.showAndroidNotifications.collectAsState()
    val filteredApps by vm.filteredApps.collectAsState()
    val notifications by vm.notifications.collectAsState()

    // Collect distinct app identifiers seen in current notification history
    val knownApps = remember(notifications) {
        notifications.map { it.appIdentifier }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item { SectionHeader("Display") }

            item {
                ToggleRow(
                    icon = Icons.Default.Visibility,
                    title = "Show notification body",
                    subtitle = "Display message text in notification cards",
                    checked = showBody,
                    onToggle = { vm.setShowBody(it) }
                )
            }

            item {
                ToggleRow(
                    icon = Icons.Default.Notifications,
                    title = "Show Android notifications",
                    subtitle = "Mirror iPhone notifications in Android notification shade",
                    checked = showAndroidNotifications,
                    onToggle = { vm.setShowAndroidNotifications(it) }
                )
            }

            item {
                ToggleRow(
                    icon = Icons.Default.ScreenLockPortrait,
                    title = "Keep screen awake",
                    subtitle = "Prevent screen from sleeping while app is open",
                    checked = keepScreenAwake,
                    onToggle = { vm.setKeepScreenAwake(it) }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Connection") }

            item {
                ToggleRow(
                    icon = Icons.Default.Replay,
                    title = "Auto-reconnect",
                    subtitle = "Automatically reconnect if Bluetooth drops",
                    checked = autoReconnect,
                    onToggle = { vm.setAutoReconnect(it) }
                )
            }

            if (knownApps.isNotEmpty()) {
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                item { SectionHeader("Filtered Apps") }
                item {
                    Text(
                        "Tap an app to hide its notifications",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                items(knownApps) { bundleId ->
                    AppFilterRow(
                        bundleId = bundleId,
                        isFiltered = bundleId in filteredApps,
                        onToggle = { vm.toggleFilteredApp(bundleId) }
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Data") }

            item {
                val clearSource = remember { MutableInteractionSource() }
                ListItem(
                    headlineContent = { Text("Clear all notifications") },
                    supportingContent = { Text("Remove all mirrored notifications from the dashboard") },
                    leadingContent = {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable(
                        interactionSource = clearSource,
                        indication = null
                    ) { vm.clearNotifications() }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    )
}

@Composable
private fun AppFilterRow(bundleId: String, isFiltered: Boolean, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(AppNameFormatter.format(bundleId)) },
        supportingContent = { Text(bundleId, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            Icon(Icons.Default.Apps, contentDescription = null)
        },
        trailingContent = {
            Switch(checked = isFiltered, onCheckedChange = { onToggle() })
        }
    )
}


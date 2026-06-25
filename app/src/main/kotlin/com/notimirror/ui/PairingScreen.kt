package com.notimirror.ui

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.notimirror.service.AncsForegroundService
import com.notimirror.service.ACTION_CONNECT
import com.notimirror.service.ACTION_DISCONNECT
import com.notimirror.service.EXTRA_DEVICE_ADDRESS
import com.notimirror.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scanResults by vm.scanResults.collectAsState()
    val isScanning by vm.isScanning.collectAsState()
    var showOnboarding by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { vm.startScan() }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to iPhone") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showOnboarding) {
                item {
                    OnboardingCard(onDismiss = { showOnboarding = false })
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Nearby Devices", style = MaterialTheme.typography.titleSmall)
                    if (isScanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Scanning…", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        TextButton(onClick = { vm.startScan() }) { Text("Scan again") }
                    }
                }
            }

            if (scanResults.isEmpty() && !isScanning) {
                item {
                    Text(
                        "No devices found. Make sure your iPhone is nearby and Bluetooth is on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            items(scanResults, key = { it.device.address }) { result ->
                DeviceRow(result) { address ->
                    connectToDevice(context, address, vm)
                    onBack()
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                OutlinedButton(
                    onClick = { disconnectService(context); onBack() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Disconnect")
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceRow(result: ScanResult, onConnect: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConnect(result.device.address) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.PhoneIphone, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.device.name ?: "Unknown",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    result.device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Text(
                "${result.rssi} dBm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun OnboardingCard(onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Setup Instructions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            val steps = listOf(
                "On your iPhone: Settings → Bluetooth → pair with this Android device.",
                "After pairing succeeds, open your iPhone's Bluetooth settings again.",
                "Tap the (i) next to this Android device and enable Share System Notifications.",
                "Come back here and tap your iPhone in the device list below.",
                "NotiMirror will start mirroring your iPhone notifications."
            )
            steps.forEachIndexed { i, step ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        "${i + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(
                        step,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Got it")
            }
        }
    }
}

private fun connectToDevice(context: Context, address: String, vm: MainViewModel) {
    // Save the device address for auto-reconnect
    vm.setLastDeviceAddress(address)

    val intent = Intent(context, AncsForegroundService::class.java).apply {
        action = ACTION_CONNECT
        putExtra(EXTRA_DEVICE_ADDRESS, address)
    }
    context.startForegroundService(intent)
}

private fun disconnectService(context: Context) {
    val intent = Intent(context, AncsForegroundService::class.java).apply {
        action = ACTION_DISCONNECT
    }
    context.startService(intent)
}

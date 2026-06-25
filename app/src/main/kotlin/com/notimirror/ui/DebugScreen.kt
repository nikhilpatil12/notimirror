package com.notimirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notimirror.ble.ConnectionState
import com.notimirror.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(vm: MainViewModel, onBack: () -> Unit) {
    val debugEvents by vm.debugEvents.collectAsState()
    val connectionState by vm.connectionState.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll only when new events arrive, not on every recomposition
    LaunchedEffect(debugEvents.firstOrNull()) {
        if (debugEvents.isNotEmpty() && listState.firstVisibleItemIndex <= 5) {
            listState.scrollToItem(0)  // Use scrollToItem instead of animateScrollToItem for performance
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug — ANCS Events") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.clearDebugEvents() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear log")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection state banner
            Surface(
                color = when (connectionState) {
                    is ConnectionState.ServiceDiscovered -> MaterialTheme.colorScheme.primaryContainer
                    ConnectionState.Disconnected -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "State: ${connectionState::class.simpleName}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            if (debugEvents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                ) {
                    Text(
                        "No events yet. Connect to an iPhone to see raw ANCS events here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        count = debugEvents.size,
                        key = { index -> "$index-${debugEvents[index].hashCode()}" }
                    ) { index ->
                        Text(
                            text = debugEvents[index],
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

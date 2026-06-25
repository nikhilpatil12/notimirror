package com.notimirror.viewmodel

import android.bluetooth.le.ScanResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notimirror.ble.BleConnectionManager
import com.notimirror.ble.BleScanner
import com.notimirror.ble.ConnectionState
import com.notimirror.ble.ScanEvent
import com.notimirror.data.AppSettings
import com.notimirror.data.IPhoneNotification
import com.notimirror.data.NotificationRepository
import com.notimirror.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    val repository: NotificationRepository,
    val connectionManager: BleConnectionManager,
    val bleScanner: BleScanner,
    val settings: AppSettings
) : ViewModel() {

    val notifications: StateFlow<List<IPhoneNotification>> = repository.notifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.Disconnected)

    val debugEvents: StateFlow<List<String>> = repository.debugEvents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val showBody: StateFlow<Boolean> = settings.showBody
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val keepScreenAwake: StateFlow<Boolean> = settings.keepScreenAwake
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoReconnect: StateFlow<Boolean> = settings.autoReconnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val filteredApps: StateFlow<Set<String>> = settings.filteredApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val showAndroidNotifications: StateFlow<Boolean> = settings.showAndroidNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val lastDeviceAddress: StateFlow<String?> = settings.lastDeviceAddress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun startScan() {
        _scanResults.update { emptyList() }
        _isScanning.update { true }
        viewModelScope.launch {
            bleScanner.scanForAllDevices().collect { event ->
                when (event) {
                    is ScanEvent.DeviceFound -> {
                        _scanResults.update { current ->
                            val existing = current.indexOfFirst { it.device.address == event.result.device.address }
                            if (existing >= 0) current else current + event.result
                        }
                    }
                    is ScanEvent.ScanFailed -> _isScanning.update { false }
                }
            }
        }
    }

    fun stopScan() { _isScanning.update { false } }

    fun clearNotifications() = repository.clear()
    fun clearDebugEvents() = repository.clearDebugEvents()

    fun setShowBody(v: Boolean) = viewModelScope.launch { settings.setShowBody(v) }
    fun setKeepScreenAwake(v: Boolean) = viewModelScope.launch { settings.setKeepScreenAwake(v) }
    fun setAutoReconnect(v: Boolean) = viewModelScope.launch { settings.setAutoReconnect(v) }
    fun setShowAndroidNotifications(v: Boolean) = viewModelScope.launch { settings.setShowAndroidNotifications(v) }
    fun toggleFilteredApp(bundleId: String) = viewModelScope.launch { settings.toggleFilteredApp(bundleId) }
    fun setLastDeviceAddress(address: String) = viewModelScope.launch { settings.setLastDeviceAddress(address) }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(
                container.repository,
                container.connectionManager,
                container.bleScanner,
                container.settings
            ) as T
    }
}

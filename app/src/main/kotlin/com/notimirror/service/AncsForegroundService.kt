package com.notimirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.notimirror.MainActivity
import com.notimirror.NotiMirrorApp
import com.notimirror.R
import com.notimirror.ble.AncsClient
import com.notimirror.ble.BleConnectionManager
import com.notimirror.ble.BleScanner
import com.notimirror.ble.ConnectionState
import com.notimirror.ble.ScanEvent
import com.notimirror.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val TAG = "AncsForegroundService"
private const val CHANNEL_ID = "ancs_service"
private const val NOTIFICATION_ID = 1

const val ACTION_CONNECT = "com.notimirror.action.CONNECT"
const val ACTION_DISCONNECT = "com.notimirror.action.DISCONNECT"
const val EXTRA_DEVICE_ADDRESS = "device_address"

class AncsForegroundService : Service() {

    private val app get() = application as NotiMirrorApp
    private val bleScanner: BleScanner get() = app.container.bleScanner
    private val connectionManager: BleConnectionManager get() = app.container.connectionManager
    @Suppress("unused") // referenced to force init of BLE event loop
    private val ancsClient: AncsClient get() = app.container.ancsClient
    private val settings: AppSettings get() = app.container.settings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var targetAddress: String? = null
    private var reconnectEnabled = true
    private var reconnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Touch ancsClient to ensure its init {} block runs before BLE events arrive
        app.container.ancsClient
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for iPhone…"))
        observeConnectionState()

        // Load last connected device from settings
        scope.launch {
            targetAddress = settings.lastDeviceAddress.first()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                cancelPendingReconnect()
                reconnectEnabled = true
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address != null) {
                    targetAddress = address
                    scope.launch { settings.setLastDeviceAddress(address) }
                    reconnectToAddress(address)
                } else {
                    scanAndConnect()
                }
            }
            ACTION_DISCONNECT -> {
                reconnectEnabled = false
                cancelPendingReconnect()
                connectionManager.disconnect()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelPendingReconnect()
        scope.cancel()
        connectionManager.disconnect()
        super.onDestroy()
    }

    private fun reconnectToAddress(address: String) {
        scope.launch {
            @Suppress("MissingPermission")
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val device = adapter.getRemoteDevice(address)
            connectionManager.connect(device)
        }
    }

    /**
     * Scans for a BLE peripheral advertising the ANCS service UUID and connects
     * to the first one found.  This works only after the iPhone is already bonded.
     */
    private fun scanAndConnect() {
        scope.launch {
            cancelPendingReconnect()
            updateNotification("Scanning for iPhone…")
            val event = bleScanner.scanForAncsDevices()
                .filterIsInstance<ScanEvent.DeviceFound>()
                .first()

            val device = event.result.device
            targetAddress = device.address
            settings.setLastDeviceAddress(device.address)
            Log.d(TAG, "Found ANCS device: ${device.address}")
            updateNotification("Connecting to ${device.name ?: device.address}…")
            connectionManager.connect(device)
        }
    }

    private fun observeConnectionState() {
        connectionManager.connectionState.onEach { state ->
            when (state) {
                is ConnectionState.ServiceDiscovered -> {
                    cancelPendingReconnect()
                    updateNotification(if (state.hasAncs) "iPhone connected" else "Connected (no ANCS)")
                }
                ConnectionState.Disconnected -> {
                    updateNotification("Disconnected")
                    maybeReconnect()
                }
                ConnectionState.Connecting -> {
                    cancelPendingReconnect()
                    updateNotification("Connecting…")
                }
                ConnectionState.Connected -> {
                    cancelPendingReconnect()
                    updateNotification("Discovering services…")
                }
                is ConnectionState.Error -> {
                    updateNotification("Error: ${state.message}")
                    maybeReconnect()
                }
            }
        }.launchIn(scope)
    }

    private fun maybeReconnect() {
        if (!reconnectEnabled) return
        val address = targetAddress ?: return
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            val autoReconnect = settings.autoReconnect.first()
            if (!autoReconnect) return@launch
            Log.d(TAG, "Auto-reconnecting in 5s…")
            delay(5_000)
            reconnectToAddress(address)
        }
    }

    private fun cancelPendingReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ANCS Background Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Bluetooth connection to your iPhone alive"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NotiMirror")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}

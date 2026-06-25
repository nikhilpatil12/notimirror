package com.notimirror.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "BleConnectionManager"

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class ServiceDiscovered(val hasAncs: Boolean) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class GattEvent {
    data class CharacteristicChanged(val uuid: UUID, val data: ByteArray) : GattEvent()
    data class WriteResult(val uuid: UUID, val status: Int) : GattEvent()
}

@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _gattEvents = MutableSharedFlow<GattEvent>(replay = 0, extraBufferCapacity = 64)
    val gattEvents: SharedFlow<GattEvent> = _gattEvents.asSharedFlow()

    // Pending write queue — Android BLE allows only one in-flight write at a time
    private val writeQueue = ArrayDeque<Pair<UUID, ByteArray>>()
    private var writeInFlight = false

    // Deduplicate BLE callbacks - Android delivers duplicates across threads
    // Map of UUID -> (packet hash, timestamp)
    private val recentPackets = mutableMapOf<UUID, String>()
    private var lastCleanupTime = 0L

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    Log.d(TAG, "Connected to GATT, discovering services…")
                    _connectionState.value = ConnectionState.Connected
                    gatt.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT (status=$status)")
                    _connectionState.value = ConnectionState.Disconnected
                    gatt.close()
                    this@BleConnectionManager.gatt = null
                }
                else -> {
                    Log.w(TAG, "GATT status=$status newState=$newState")
                    _connectionState.value = ConnectionState.Error("GATT error status=$status")
                    gatt.close()
                    this@BleConnectionManager.gatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error("Service discovery failed status=$status")
                return
            }
            val hasAncs = gatt.getService(AncsUuids.SERVICE) != null
            Log.d(TAG, "Services discovered, hasAncs=$hasAncs")
            _connectionState.value = ConnectionState.ServiceDiscovered(hasAncs)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // CRITICAL: Copy byte array IMMEDIATELY - Android BLE reuses buffers
            val dataCopy = value.copyOf()

            // Deduplicate synchronously - Android delivers same packet to multiple threads
            synchronized(recentPackets) {
                // Periodically clean up before recording this packet. If cleanup happens
                // after recording, the first packet in a cleanup window deletes its own
                // fingerprint and an immediate duplicate can slip through.
                val now = System.currentTimeMillis()
                if (now - lastCleanupTime > 500) {
                    recentPackets.clear()
                    lastCleanupTime = now
                }

                val packetHash = dataCopy.joinToString("") { "%02X".format(it) }
                val uuid = characteristic.uuid

                // Check if we've seen this exact packet for this characteristic
                if (recentPackets[uuid] == packetHash) {
                    Log.d(TAG, "Skipping duplicate packet for $uuid")
                    return
                }

                // Track this packet
                recentPackets[uuid] = packetHash
            }

            Log.d(TAG, "Characteristic changed: ${characteristic.uuid}, ${dataCopy.size} bytes")
            scope.launch {
                _gattEvents.emit(GattEvent.CharacteristicChanged(characteristic.uuid, dataCopy))
            }
        }

        @Deprecated("Used on older API levels")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            // CRITICAL: Copy byte array IMMEDIATELY - Android BLE reuses buffers
            val dataCopy = value.copyOf()

            // Deduplicate synchronously
            synchronized(recentPackets) {
                val now = System.currentTimeMillis()
                if (now - lastCleanupTime > 500) {
                    recentPackets.clear()
                    lastCleanupTime = now
                }

                val packetHash = dataCopy.joinToString("") { "%02X".format(it) }
                val uuid = characteristic.uuid

                if (recentPackets[uuid] == packetHash) {
                    Log.d(TAG, "Skipping duplicate packet for $uuid")
                    return
                }

                recentPackets[uuid] = packetHash
            }

            scope.launch {
                _gattEvents.emit(GattEvent.CharacteristicChanged(characteristic.uuid, dataCopy))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            scope.launch {
                _gattEvents.emit(GattEvent.WriteResult(characteristic.uuid, status))
                writeInFlight = false
                drainWriteQueue()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "Descriptor write ${descriptor.uuid} status=$status")
        }
    }

    fun connect(device: BluetoothDevice) {
        connectedDevice = device
        Log.d(TAG, "Connecting to ${device.address}")
        _connectionState.value = ConnectionState.Connecting
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun reconnect() {
        val device = connectedDevice ?: return
        gatt?.close()
        gatt = null
        scope.launch {
            delay(1500)
            connect(device)
        }
    }

    /**
     * Enables notifications on a characteristic by writing 0x0100 to its CCCD descriptor.
     * ANCS Notification Source and Data Source both require this before events are delivered.
     */
    fun enableNotifications(characteristicUuid: UUID): Boolean {
        val service = gatt?.getService(AncsUuids.SERVICE) ?: return false
        val characteristic = service.getCharacteristic(characteristicUuid) ?: return false

        gatt?.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(AncsUuids.CLIENT_CHARACTERISTIC_CONFIG)
            ?: return false

        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            gatt?.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt?.writeDescriptor(descriptor) ?: false
        }
    }

    /**
     * Queued write to the ANCS Control Point characteristic.
     * Android BLE only allows one in-flight GATT write; this queue serialises them.
     */
    fun writeControlPoint(data: ByteArray) {
        writeQueue.addLast(Pair(AncsUuids.CONTROL_POINT, data))
        drainWriteQueue()
    }

    private fun drainWriteQueue() {
        if (writeInFlight || writeQueue.isEmpty()) return
        val (uuid, data) = writeQueue.removeFirst()
        performWrite(uuid, data)
    }

    private fun performWrite(uuid: UUID, data: ByteArray) {
        val service = gatt?.getService(AncsUuids.SERVICE) ?: return
        val characteristic = service.getCharacteristic(uuid) ?: return
        writeInFlight = true

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            gatt?.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(characteristic)
        }
    }

    fun performNotificationAction(uid: Int, isPositive: Boolean) {
        val actionId = if (isPositive) NotificationActionId.POSITIVE else NotificationActionId.NEGATIVE
        val command = buildPerformNotificationActionCommand(uid, actionId)
        writeControlPoint(command)
    }

    fun isConnected() = _connectionState.value is ConnectionState.ServiceDiscovered
}

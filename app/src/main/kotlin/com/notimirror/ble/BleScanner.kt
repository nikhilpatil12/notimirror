package com.notimirror.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed class ScanEvent {
    data class DeviceFound(val result: ScanResult) : ScanEvent()
    data class ScanFailed(val errorCode: Int) : ScanEvent()
}

class BleScanner(private val bluetoothAdapter: BluetoothAdapter) {

    /**
     * Scans for BLE peripherals that advertise the ANCS service UUID.
     *
     * iPhones advertise ANCS (7905F431-…) in their scan response once a Bluetooth
     * bond/pairing exists between the two devices.  If the devices are not yet paired
     * you will not see ANCS in the advertisement — show the pairing UI first.
     *
     * Returns a cold Flow; scan stops when the collector is cancelled.
     */
    @SuppressLint("MissingPermission")
    fun scanForAncsDevices(): Flow<ScanEvent> = callbackFlow {
        val scanner: BluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            ?: run {
                close(IllegalStateException("BLE not available"))
                return@callbackFlow
            }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(AncsUuids.SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(ScanEvent.DeviceFound(result))
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { trySend(ScanEvent.DeviceFound(it)) }
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(ScanEvent.ScanFailed(errorCode))
                close()
            }
        }

        scanner.startScan(listOf(filter), settings, callback)

        awaitClose {
            scanner.stopScan(callback)
        }
    }

    /**
     * Broad scan with no service filter — used on the pairing screen before
     * the bond is formed and ANCS UUID may not yet appear in advertisements.
     */
    @SuppressLint("MissingPermission")
    fun scanForAllDevices(): Flow<ScanEvent> = callbackFlow {
        val scanner: BluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            ?: run {
                close(IllegalStateException("BLE not available"))
                return@callbackFlow
            }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name != null) {
                    trySend(ScanEvent.DeviceFound(result))
                }
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.filter { it.device.name != null }
                    .forEach { trySend(ScanEvent.DeviceFound(it)) }
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(ScanEvent.ScanFailed(errorCode))
                close()
            }
        }

        scanner.startScan(null, settings, callback)

        awaitClose {
            scanner.stopScan(callback)
        }
    }
}

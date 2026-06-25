package com.notimirror.di

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import com.notimirror.ble.AncsClient
import com.notimirror.ble.BleConnectionManager
import com.notimirror.ble.BleScanner
import com.notimirror.data.AppSettings
import com.notimirror.data.NotificationRepository

// Manual singleton DI container — created once in NotiMirrorApp.
class AppContainer(app: Application) {
    private val bluetoothAdapter =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    val settings = AppSettings(app)
    val repository = NotificationRepository(app, settings)
    val bleScanner = BleScanner(bluetoothAdapter)
    val connectionManager = BleConnectionManager(app)
    val ancsClient = AncsClient(connectionManager, repository) // wires BLE event loop on init
}

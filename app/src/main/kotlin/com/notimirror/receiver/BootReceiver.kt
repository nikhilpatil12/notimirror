package com.notimirror.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.notimirror.NotiMirrorApp
import com.notimirror.service.AncsForegroundService
import com.notimirror.service.ACTION_CONNECT
import com.notimirror.service.EXTRA_DEVICE_ADDRESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        Log.d("BootReceiver", "Boot completed, checking for auto-reconnect")

        val app = context.applicationContext as NotiMirrorApp
        val settings = app.container.settings

        // Check if we have a saved device and auto-reconnect is enabled
        CoroutineScope(Dispatchers.IO).launch {
            val lastDevice = settings.lastDeviceAddress.first()
            val autoReconnect = settings.autoReconnect.first()

            if (lastDevice != null && autoReconnect) {
                Log.d("BootReceiver", "Starting service with saved device: $lastDevice")

                val serviceIntent = Intent(context, AncsForegroundService::class.java).apply {
                    action = ACTION_CONNECT
                    putExtra(EXTRA_DEVICE_ADDRESS, lastDevice)
                }

                context.startForegroundService(serviceIntent)
            }
        }
    }
}
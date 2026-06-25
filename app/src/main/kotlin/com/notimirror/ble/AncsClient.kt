package com.notimirror.ble

import android.util.Log
import com.notimirror.data.AncsEventId
import com.notimirror.data.IPhoneNotification
import com.notimirror.data.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.time.Instant

private const val TAG = "AncsClient"

/**
 * Orchestrates the ANCS protocol on top of BleConnectionManager.
 *
 * After GATT services are discovered this class:
 *  1. Subscribes to Notification Source (to learn about new/removed notifications).
 *  2. Subscribes to Data Source (to receive attribute payloads).
 *  3. For each Added/Modified event it writes a GetNotificationAttributes command to
 *     the Control Point so iOS will push the full notification details.
 *  4. Hands parsed attributes to NotificationRepository.
 */
class AncsClient(
    private val connectionManager: BleConnectionManager,
    private val repository: NotificationRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataSourceParser = DataSourceParser()

    init {
        observeConnectionState()
        observeGattEvents()
    }

    private fun observeConnectionState() {
        connectionManager.connectionState.onEach { state ->
            if (state is ConnectionState.ServiceDiscovered && state.hasAncs) {
                Log.d(TAG, "ANCS service found — subscribing to characteristics")
                subscribeToAncsCharacteristics()
            }
        }.launchIn(scope)
    }

    private fun observeGattEvents() {
        connectionManager.gattEvents.onEach { event ->
            when (event) {
                is GattEvent.CharacteristicChanged -> handleCharacteristicChanged(event)
                is GattEvent.WriteResult -> {
                    if (event.status != 0) {
                        Log.w(TAG, "Control Point write failed status=${event.status}")
                        repository.logDebugEvent("CP write error status=${event.status}")
                    }
                }
            }
        }.launchIn(scope)
    }

    private fun subscribeToAncsCharacteristics() {
        val nsOk = connectionManager.enableNotifications(AncsUuids.NOTIFICATION_SOURCE)
        val dsOk = connectionManager.enableNotifications(AncsUuids.DATA_SOURCE)
        Log.d(TAG, "NS subscription=$nsOk, DS subscription=$dsOk")
        repository.logDebugEvent("Subscribed NS=$nsOk DS=$dsOk")
    }

    private fun handleCharacteristicChanged(event: GattEvent.CharacteristicChanged) {
        when (event.uuid) {
            AncsUuids.NOTIFICATION_SOURCE -> handleNotificationSource(event.data)
            AncsUuids.DATA_SOURCE         -> handleDataSource(event.data)
        }
    }

    /**
     * Notification Source handler — called for every iOS notification lifecycle event.
     *
     * For Added/Modified we immediately request full attributes via Control Point.
     * For Removed we delete the notification from the repository.
     */
    private fun handleNotificationSource(data: ByteArray) {
        val event = parseNotificationSource(data) ?: run {
            Log.w(TAG, "Failed to parse NS packet: ${data.toHex()}")
            repository.logDebugEvent("NS parse error: ${data.toHex()}")
            return
        }

        val debugLine = "NS uid=${event.notificationUid} " +
            "event=${event.eventId.name} " +
            "cat=${event.categoryId.name} " +
            "flags=0x${event.eventFlags.raw.toHex()}"
        Log.d(TAG, debugLine)
        repository.logDebugEvent(debugLine)

        when (event.eventId) {
            AncsEventId.NotificationAdded,
            AncsEventId.NotificationModified -> {
                // Store a placeholder immediately so UI can show category/flags
                // while attribute fetch is in flight
                repository.addOrUpdate(
                    IPhoneNotification(
                        uid           = event.notificationUid,
                        eventId       = event.eventId,
                        eventFlags    = event.eventFlags,
                        categoryId    = event.categoryId,
                        categoryCount = event.categoryCount,
                        receivedAt    = Instant.now()
                    )
                )
                val cmd = buildGetNotificationAttributesCommand(event.notificationUid)
                repository.logDebugEvent("→ GetNotifAttrs uid=${event.notificationUid} cmd=${cmd.toHex()}")
                connectionManager.writeControlPoint(cmd)
            }
            AncsEventId.NotificationRemoved -> {
                repository.remove(event.notificationUid)
            }
        }
    }

    /**
     * Data Source handler — assembles potentially fragmented attribute response packets.
     *
     * iOS may split a long attribute response across multiple BLE MTU-sized packets.
     * DataSourceParser buffers bytes until it sees a complete response.
     */
    private fun handleDataSource(data: ByteArray) {
        val debugLine = "DS ${data.size}B: ${data.toHex()}"
        Log.d(TAG, debugLine)
        repository.logDebugEvent(debugLine)

        val parsed = dataSourceParser.append(data) ?: return

        val resultLine = "DS parsed uid=${parsed.uid} app=${parsed.appIdentifier} " +
            "title='${parsed.title}' msg='${parsed.message}'"
        Log.d(TAG, resultLine)
        repository.logDebugEvent(resultLine)

        repository.updateAttributes(
            uid           = parsed.uid,
            appIdentifier = parsed.appIdentifier,
            title         = parsed.title,
            subtitle      = parsed.subtitle,
            message       = parsed.message,
            date          = parsed.date
        )
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
fun Byte.toHex(): String = "%02X".format(this)

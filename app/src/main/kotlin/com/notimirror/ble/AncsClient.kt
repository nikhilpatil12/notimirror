package com.notimirror.ble

import android.util.Log
import com.notimirror.data.AncsEventId
import com.notimirror.data.IPhoneNotification
import com.notimirror.data.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.Instant

private const val TAG = "AncsClient"
private const val CONTROL_POINT_RESPONSE_TIMEOUT_MS = 4_000L
private const val CONTROL_POINT_REQUEST_SPACING_MS = 150L

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

    // Cache of app bundle ID -> display name fetched from iOS
    private val appNameCache = mutableMapOf<String, String>()
    private val appNameRequests = mutableSetOf<String>() // Track pending requests
    private val controlPointQueue = ArrayDeque<PendingAncsRequest>()
    private var inFlightRequest: PendingAncsRequest? = null
    private var responseTimeoutJob: Job? = null
    private var drainPaused = false

    init {
        observeConnectionState()
        observeGattEvents()
    }

    private fun observeConnectionState() {
        connectionManager.connectionState.onEach { state ->
            when (state) {
                is ConnectionState.ServiceDiscovered -> {
                    resetProtocolState()
                    if (state.hasAncs) {
                        Log.d(TAG, "ANCS service found — subscribing to characteristics")
                        subscribeToAncsCharacteristics()
                    }
                }
                ConnectionState.Disconnected,
                is ConnectionState.Error -> resetProtocolState()
                else -> Unit
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
                        completeInFlightRequest(success = false)
                    }
                }
            }
        }.launchIn(scope)
    }

    private fun subscribeToAncsCharacteristics() {
        scope.launch {
            val nsOk = connectionManager.enableNotifications(AncsUuids.NOTIFICATION_SOURCE)
            delay(200)  // Wait for NS descriptor write to complete
            val dsOk = connectionManager.enableNotifications(AncsUuids.DATA_SOURCE)
            Log.d(TAG, "NS subscription=$nsOk, DS subscription=$dsOk")
            repository.logDebugEvent("Subscribed NS=$nsOk DS=$dsOk")
        }
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
                enqueueControlPointRequest(
                    PendingAncsRequest.NotificationAttributes(
                        uid = event.notificationUid,
                        command = cmd
                    )
                )
            }
            AncsEventId.NotificationRemoved -> {
                // Cancel Android system notification (stops vibration) but keep in repository list
                Log.d(TAG, "NotificationRemoved uid=${event.notificationUid} - calling repository.remove()")
                repository.logDebugEvent("NotificationRemoved uid=${event.notificationUid} (canceling system notification)")
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
    @Synchronized
    private fun handleDataSource(data: ByteArray) {
        if (repository.isVerboseDebugLoggingEnabled()) {
            val debugLine = "DS ${data.size}B: ${data.toHex()}"
            Log.d(TAG, debugLine)
            repository.logVerboseDebugEvent(debugLine)
        }

        val parsed = dataSourceParser.append(data) ?: return
        if (!matchesInFlightRequest(parsed)) {
            Log.w(TAG, "Ignoring stale ANCS Data Source response: $parsed")
            repository.logDebugEvent("Ignoring stale ANCS response")
            return
        }

        when (parsed) {
            is ParsedNotificationAttributes -> {
                handleNotificationAttributesResponse(parsed)
                completeInFlightRequest(success = true)
            }
            is ParsedAppAttributes -> {
                handleAppAttributesResponse(parsed)
                completeInFlightRequest(success = true)
            }
        }
    }

    private fun handleNotificationAttributesResponse(parsed: ParsedNotificationAttributes) {
        if (repository.isVerboseDebugLoggingEnabled()) {
            // Log raw attributes received with their IDs and lengths
            val attrDetails = if (parsed.rawAttrs.isEmpty()) {
                "NO ATTRIBUTES RECEIVED"
            } else {
                parsed.rawAttrs.entries.joinToString(", ") { (id, value) ->
                    val attrName = when (id) {
                        NotificationAttributeId.APP_IDENTIFIER -> "AppID"
                        NotificationAttributeId.TITLE -> "Title"
                        NotificationAttributeId.SUBTITLE -> "Subtitle"
                        NotificationAttributeId.MESSAGE -> "Message"
                        NotificationAttributeId.DATE -> "Date"
                        else -> "Attr$id"
                    }
                    if (value.isEmpty()) {
                        "$attrName(EMPTY)"
                    } else {
                        "$attrName(${value.length}): '${value.take(50)}${if (value.length > 50) "..." else ""}'"
                    }
                }
            }

            val resultLine = "DS parsed uid=${parsed.uid} attrs: $attrDetails"
            Log.d(TAG, resultLine)
            repository.logVerboseDebugEvent(resultLine)
        }

        // Additional debug if app identifier is missing
        if (parsed.appIdentifier.isEmpty()) {
            Log.w(TAG, "WARNING: App identifier is empty for uid=${parsed.uid}")
            repository.logDebugEvent("⚠️ App identifier missing for uid=${parsed.uid}")
        }

        // Request app display name if we haven't seen this app before
        if (parsed.appIdentifier.isNotBlank() &&
            repository.getAppDisplayName(parsed.appIdentifier) == parsed.appIdentifier &&
            parsed.appIdentifier !in appNameCache &&
            parsed.appIdentifier !in appNameRequests) {
            requestAppDisplayName(parsed.appIdentifier)
        }

        repository.updateAttributes(
            uid           = parsed.uid,
            appIdentifier = parsed.appIdentifier,
            title         = parsed.title,
            subtitle      = parsed.subtitle,
            message       = parsed.message,
            date          = parsed.date
        )
    }

    private fun handleAppAttributesResponse(parsed: ParsedAppAttributes) {
        Log.d(TAG, "Received app name: ${parsed.appIdentifier} = '${parsed.displayName}'")
        repository.logDebugEvent("📱 App name: ${parsed.appIdentifier} = '${parsed.displayName}'")

        // Cache the display name
        appNameCache[parsed.appIdentifier] = parsed.displayName
        appNameRequests.remove(parsed.appIdentifier)

        // Update repository with the new app name for display
        repository.updateAppDisplayName(parsed.appIdentifier, parsed.displayName)
    }

    private fun requestAppDisplayName(appIdentifier: String) {
        Log.d(TAG, "Requesting display name for: $appIdentifier")

        appNameRequests.add(appIdentifier)
        val cmd = buildGetAppAttributesCommand(appIdentifier)
        enqueueControlPointRequest(
            PendingAncsRequest.AppAttributes(
                appIdentifier = appIdentifier,
                command = cmd
            )
        )
    }

    /**
     * Get cached app display name or return the bundle ID.
     * Public method for NotificationRepository to use.
     */
    fun getAppDisplayName(appIdentifier: String): String {
        return appNameCache[appIdentifier] ?: appIdentifier
    }

    @Synchronized
    private fun enqueueControlPointRequest(request: PendingAncsRequest) {
        controlPointQueue.addLast(request)
        drainControlPointQueue()
    }

    @Synchronized
    private fun drainControlPointQueue() {
        if (drainPaused || inFlightRequest != null || controlPointQueue.isEmpty()) return

        val request = controlPointQueue.removeFirst()
        inFlightRequest = request
        dataSourceParser.reset()

        when (request) {
            is PendingAncsRequest.NotificationAttributes ->
                repository.logVerboseDebugEvent("→ GetNotifAttrs uid=${request.uid} cmd=${request.command.toHex()}")
            is PendingAncsRequest.AppAttributes ->
                repository.logVerboseDebugEvent("→ GetAppAttrs for ${request.appIdentifier} cmd=${request.command.toHex()}")
        }

        connectionManager.writeControlPoint(request.command)
        startResponseTimeout(request)
    }

    @Synchronized
    private fun completeInFlightRequest(success: Boolean) {
        val completed = inFlightRequest ?: return
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
        inFlightRequest = null
        drainPaused = true

        if (!success && completed is PendingAncsRequest.AppAttributes) {
            appNameRequests.remove(completed.appIdentifier)
        }

        scope.launch {
            delay(CONTROL_POINT_REQUEST_SPACING_MS)
            resumeControlPointQueue()
        }
    }

    @Synchronized
    private fun resetProtocolState() {
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
        inFlightRequest = null
        drainPaused = false
        controlPointQueue.clear()
        appNameRequests.clear()
        dataSourceParser.reset()
    }

    @Synchronized
    private fun startResponseTimeout(request: PendingAncsRequest) {
        responseTimeoutJob?.cancel()
        responseTimeoutJob = scope.launch {
            delay(CONTROL_POINT_RESPONSE_TIMEOUT_MS)
            handleResponseTimeout(request)
        }
    }

    @Synchronized
    private fun handleResponseTimeout(request: PendingAncsRequest) {
        if (inFlightRequest !== request) return

        val label = when (request) {
            is PendingAncsRequest.NotificationAttributes -> "notification uid=${request.uid}"
            is PendingAncsRequest.AppAttributes -> "app ${request.appIdentifier}"
        }
        Log.w(TAG, "Timed out waiting for ANCS Data Source response for $label")
        repository.logDebugEvent("ANCS response timeout for $label")
        dataSourceParser.reset()
        completeInFlightRequest(success = false)
    }

    @Synchronized
    private fun matchesInFlightRequest(response: DataSourceResponse): Boolean {
        val request = inFlightRequest ?: return false
        return when {
            request is PendingAncsRequest.NotificationAttributes && response is ParsedNotificationAttributes ->
                request.uid == response.uid
            request is PendingAncsRequest.AppAttributes && response is ParsedAppAttributes ->
                request.appIdentifier == response.appIdentifier
            else -> false
        }
    }

    @Synchronized
    private fun resumeControlPointQueue() {
        drainPaused = false
        drainControlPointQueue()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
fun Byte.toHex(): String = "%02X".format(this)

private sealed class PendingAncsRequest {
    abstract val command: ByteArray

    data class NotificationAttributes(
        val uid: Int,
        override val command: ByteArray
    ) : PendingAncsRequest()

    data class AppAttributes(
        val appIdentifier: String,
        override val command: ByteArray
    ) : PendingAncsRequest()
}

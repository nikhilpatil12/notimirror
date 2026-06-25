package com.notimirror.data

import android.content.Context
import com.notimirror.service.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class NotificationRepository(
    private val context: Context,
    private val appSettings: AppSettings
) {
    private val notificationHelper = NotificationHelper(context)
    private val scope = CoroutineScope(Dispatchers.Main)
    private var showAndroidNotifications = false

    init {
        // Observe the setting for showing Android notifications
        appSettings.showAndroidNotifications.onEach { enabled ->
            showAndroidNotifications = enabled
        }.launchIn(scope)
    }

    private val _notifications = MutableStateFlow<List<IPhoneNotification>>(emptyList())
    val notifications: StateFlow<List<IPhoneNotification>> = _notifications.asStateFlow()

    // Raw debug log of ANCS events (capped at 200 entries)
    private val _debugEvents = MutableStateFlow<List<String>>(emptyList())
    val debugEvents: StateFlow<List<String>> = _debugEvents.asStateFlow()

    // Call history tracking
    private val _callHistory = MutableStateFlow(CallHistoryState())
    val callHistory: StateFlow<CallHistoryState> = _callHistory.asStateFlow()

    fun addOrUpdate(notification: IPhoneNotification) {
        _notifications.update { current ->
            val index = current.indexOfFirst { it.uid == notification.uid }
            if (index >= 0) {
                current.toMutableList().also { it[index] = notification }
            } else {
                // Track calls in history
                when (notification.categoryId.name) {
                    "IncomingCall" -> addCallToHistory(notification, CallType.INCOMING)
                    "MissedCall" -> addCallToHistory(notification, CallType.MISSED)
                }
                listOf(notification) + current  // newest first
            }
        }
    }

    fun remove(uid: Int) {
        // Check if this was an incoming call that was not answered (missed call)
        val removedNotification = _notifications.value.find { it.uid == uid }
        if (removedNotification?.categoryId?.name == "IncomingCall") {
            // Check if call was already marked as answered or declined in history
            val lastCall = _callHistory.value.entries.firstOrNull()
            if (lastCall?.callType == CallType.INCOMING && !lastCall.wasAnswered) {
                // This was a missed call - update it in history
                _callHistory.update { state ->
                    val updatedEntries = state.entries.toMutableList()
                    if (updatedEntries.isNotEmpty()) {
                        updatedEntries[0] = updatedEntries[0].copy(callType = CallType.MISSED)
                    }
                    state.copy(
                        entries = updatedEntries,
                        missedCallCount = state.missedCallCount + 1
                    )
                }
            }
        }

        _notifications.update { it.filter { n -> n.uid != uid } }
        // Cancel Android notification when iOS notification is removed
        if (showAndroidNotifications) {
            notificationHelper.cancelNotification(uid)
        }
    }

    fun updateAttributes(
        uid: Int,
        appIdentifier: String,
        title: String,
        subtitle: String,
        message: String,
        date: String
    ) {
        _notifications.update { current ->
            current.map { n ->
                if (n.uid == uid) {
                    val updated = n.copy(
                        appIdentifier = appIdentifier,
                        title = title,
                        subtitle = subtitle,
                        message = message,
                        date = date
                    )
                    // Show Android notification if enabled and we have content
                    if (showAndroidNotifications && (title.isNotBlank() || message.isNotBlank())) {
                        notificationHelper.showNotification(updated)
                    }
                    updated
                } else n
            }
        }
    }

    fun clear() {
        _notifications.update { emptyList() }
        // Cancel all Android notifications when clearing
        if (showAndroidNotifications) {
            notificationHelper.cancelAllNotifications()
        }
    }

    fun logDebugEvent(event: String) {
        _debugEvents.update { current ->
            (listOf(event) + current).take(200)
        }
    }

    fun clearDebugEvents() {
        _debugEvents.update { emptyList() }
    }

    // Call history methods
    private fun addCallToHistory(notification: IPhoneNotification, type: CallType) {
        val callerName = when {
            notification.title.isNotBlank() -> notification.title
            notification.subtitle.isNotBlank() -> notification.subtitle
            else -> "Unknown Caller"
        }

        val phoneNumber = when {
            notification.message.isNotBlank() -> notification.message
            notification.subtitle.isNotBlank() && notification.subtitle != callerName -> notification.subtitle
            else -> ""
        }

        val entry = CallHistoryEntry(
            callerName = callerName,
            phoneNumber = phoneNumber,
            callType = type,
            timestamp = notification.receivedAt
        )

        _callHistory.update { state ->
            val updatedEntries = (listOf(entry) + state.entries).take(100) // Keep last 100 calls
            val missedCount = if (type == CallType.MISSED) state.missedCallCount + 1 else state.missedCallCount
            CallHistoryState(
                entries = updatedEntries,
                missedCallCount = missedCount
            )
        }
    }

    fun markCallAsAnswered(notificationUid: Int) {
        // When a call is answered through the notification action
        val notification = _notifications.value.find { it.uid == notificationUid }
        if (notification?.categoryId?.name == "IncomingCall") {
            _callHistory.update { state ->
                val updatedEntries = state.entries.toMutableList()
                // Find the most recent incoming call and mark it as answered
                val index = updatedEntries.indexOfFirst {
                    it.callType == CallType.INCOMING && !it.wasAnswered
                }
                if (index >= 0) {
                    updatedEntries[index] = updatedEntries[index].copy(wasAnswered = true)
                }
                state.copy(entries = updatedEntries)
            }
        }
    }

    fun markCallAsDeclined(notificationUid: Int) {
        // When a call is declined through the notification action
        val notification = _notifications.value.find { it.uid == notificationUid }
        if (notification?.categoryId?.name == "IncomingCall") {
            _callHistory.update { state ->
                val updatedEntries = state.entries.toMutableList()
                // Find the most recent incoming call and change it to declined
                val index = updatedEntries.indexOfFirst {
                    it.callType == CallType.INCOMING && !it.wasAnswered
                }
                if (index >= 0) {
                    updatedEntries[index] = updatedEntries[index].copy(callType = CallType.DECLINED)
                }
                state.copy(entries = updatedEntries)
            }
        }
    }

    fun clearCallHistory() {
        _callHistory.update { CallHistoryState() }
    }

    fun clearMissedCallCount() {
        _callHistory.update { it.copy(missedCallCount = 0) }
    }
}

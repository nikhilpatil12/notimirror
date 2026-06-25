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

    // App display name cache (bundle ID -> display name from iOS)
    private val _appDisplayNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val appDisplayNames: StateFlow<Map<String, String>> = _appDisplayNames.asStateFlow()

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
                // Cap at 100 most recent notifications to avoid performance issues
                (listOf(notification) + current).take(100)
            }
        }
    }

    fun remove(uid: Int) {
        // Don't remove from repository list (keep accumulating like DMD2)
        // But DO cancel the Android system notification to stop vibration/sounds
        // Always cancel regardless of setting - if notification exists, stop it
        android.util.Log.d("NotificationRepository", "remove() called for uid=$uid")
        notificationHelper.cancelNotification(uid)
    }

    fun updateAttributes(
        uid: Int,
        appIdentifier: String,
        title: String,
        subtitle: String,
        message: String,
        date: String
    ) {
        // Log SMS notifications specifically for debugging
        if (appIdentifier == "com.apple.MobileSMS") {
            logDebugEvent("📱 SMS: title='$title', subtitle='$subtitle', message='${message.take(100)}'")
        }

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
                    // For SMS, prioritize message over title
                    val hasContent = if (appIdentifier == "com.apple.MobileSMS") {
                        message.isNotBlank() || title.isNotBlank() || subtitle.isNotBlank()
                    } else {
                        title.isNotBlank() || message.isNotBlank()
                    }

                    if (showAndroidNotifications && hasContent) {
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

    fun updateAppDisplayName(appIdentifier: String, displayName: String) {
        _appDisplayNames.update { current ->
            current + (appIdentifier to displayName)
        }
    }

    fun getAppDisplayName(appIdentifier: String): String {
        return _appDisplayNames.value[appIdentifier] ?: appIdentifier
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

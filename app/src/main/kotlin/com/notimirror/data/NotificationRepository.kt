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

    fun addOrUpdate(notification: IPhoneNotification) {
        _notifications.update { current ->
            val index = current.indexOfFirst { it.uid == notification.uid }
            if (index >= 0) {
                current.toMutableList().also { it[index] = notification }
            } else {
                listOf(notification) + current  // newest first
            }
        }
    }

    fun remove(uid: Int) {
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
}

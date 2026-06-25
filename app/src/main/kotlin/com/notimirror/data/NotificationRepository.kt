package com.notimirror.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class NotificationRepository {

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
                    n.copy(
                        appIdentifier = appIdentifier,
                        title = title,
                        subtitle = subtitle,
                        message = message,
                        date = date
                    )
                } else n
            }
        }
    }

    fun clear() {
        _notifications.update { emptyList() }
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

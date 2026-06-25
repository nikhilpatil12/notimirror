package com.notimirror.data

import java.time.Instant

data class CallHistoryEntry(
    val id: Long = System.currentTimeMillis(),
    val callerName: String,
    val phoneNumber: String,
    val callType: CallType,
    val timestamp: Instant = Instant.now(),
    val wasAnswered: Boolean = false,
    val duration: Long? = null // Duration in seconds, if available
)

enum class CallType {
    INCOMING,
    MISSED,
    DECLINED
}

data class CallHistoryState(
    val entries: List<CallHistoryEntry> = emptyList(),
    val missedCallCount: Int = 0
)
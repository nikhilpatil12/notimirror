package com.notimirror.data

import java.time.Instant

// EventID values from ANCS spec §3.5
enum class AncsEventId(val id: Byte) {
    NotificationAdded(0),
    NotificationModified(1),
    NotificationRemoved(2);

    companion object {
        fun from(id: Byte) = entries.firstOrNull { it.id == id }
    }
}

// CategoryID values from ANCS spec §3.7
enum class AncsCategoryId(val id: Byte) {
    Other(0),
    IncomingCall(1),
    MissedCall(2),
    Voicemail(3),
    Social(4),
    Schedule(5),
    Email(6),
    News(7),
    HealthAndFitness(8),
    BusinessAndFinance(9),
    Location(10),
    Entertainment(11);

    companion object {
        fun from(id: Byte) = entries.firstOrNull { it.id == id } ?: Other
    }
}

// EventFlags bitmask from ANCS spec §3.6
data class AncsEventFlags(val raw: Byte) {
    val isSilent: Boolean get() = (raw.toInt() and 0x01) != 0
    val isImportant: Boolean get() = (raw.toInt() and 0x02) != 0
    val isPreExisting: Boolean get() = (raw.toInt() and 0x04) != 0
    val hasPositiveAction: Boolean get() = (raw.toInt() and 0x08) != 0
    val hasNegativeAction: Boolean get() = (raw.toInt() and 0x10) != 0
}

// Raw ANCS notification source event (8 bytes, fixed format)
data class AncsNotificationEvent(
    val eventId: AncsEventId,
    val eventFlags: AncsEventFlags,
    val categoryId: AncsCategoryId,
    val categoryCount: Int,
    val notificationUid: Int   // uint32 LE
)

// Fully resolved notification with fetched attributes
data class IPhoneNotification(
    val uid: Int,
    val eventId: AncsEventId,
    val eventFlags: AncsEventFlags,
    val categoryId: AncsCategoryId,
    val categoryCount: Int,
    val appIdentifier: String = "",
    val title: String = "",
    val subtitle: String = "",
    val message: String = "",
    val date: String = "",
    val receivedAt: Instant = Instant.now()
)

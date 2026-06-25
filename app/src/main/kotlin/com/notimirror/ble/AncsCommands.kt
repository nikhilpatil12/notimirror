package com.notimirror.ble

import java.util.UUID

object AncsUuids {
    // ANCS service UUID (Apple Notification Center Service)
    val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")

    // Notifies Android of new/modified/removed iOS notifications (subscribe, read-only)
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")

    // Android writes GetNotificationAttributes / GetAppAttributes commands here
    val CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")

    // iPhone sends attribute responses here after Control Point write
    val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    // Standard GATT descriptor for enabling notifications/indications
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}

// Command IDs defined in ANCS spec §3.11.1
object AncsCommandId {
    const val GET_NOTIFICATION_ATTRIBUTES: Byte = 0
    const val GET_APP_ATTRIBUTES: Byte = 1
    const val PERFORM_NOTIFICATION_ACTION: Byte = 2
}

// Attribute IDs for GetNotificationAttributes (§3.11.2)
object NotificationAttributeId {
    const val APP_IDENTIFIER: Byte = 0
    const val TITLE: Byte = 1          // requires max-length uint16 LE suffix
    const val SUBTITLE: Byte = 2       // requires max-length uint16 LE suffix
    const val MESSAGE: Byte = 3        // requires max-length uint16 LE suffix
    const val MESSAGE_SIZE: Byte = 4
    const val DATE: Byte = 5           // yyyyMMdd'T'HHmmss
    const val POSITIVE_ACTION_LABEL: Byte = 6
    const val NEGATIVE_ACTION_LABEL: Byte = 7
}

// Action IDs for PerformNotificationAction (§3.11.3)
object NotificationActionId {
    const val POSITIVE: Byte = 0
    const val NEGATIVE: Byte = 1
}

/**
 * Builds the GetNotificationAttributes command packet (ANCS spec §3.11.2).
 *
 * Layout:
 *   [0]    CommandID = 0x00
 *   [1-4]  NotificationUID (uint32 little-endian)
 *   [5..]  AttributeID pairs: for fixed attrs just the byte;
 *          for variable-length attrs (Title, Subtitle, Message) the byte followed
 *          by a uint16 LE max-length so iOS knows how many chars to return.
 */
fun buildGetNotificationAttributesCommand(uid: Int, maxLength: UShort = 2000u): ByteArray {
    val uidBytes = intToLe32(uid)
    val maxLenBytes = ushortToLe16(maxLength)

    return byteArrayOf(
        AncsCommandId.GET_NOTIFICATION_ATTRIBUTES,
        uidBytes[0], uidBytes[1], uidBytes[2], uidBytes[3],
        NotificationAttributeId.APP_IDENTIFIER,
        NotificationAttributeId.TITLE,   maxLenBytes[0], maxLenBytes[1],
        NotificationAttributeId.SUBTITLE, maxLenBytes[0], maxLenBytes[1],
        NotificationAttributeId.MESSAGE,  maxLenBytes[0], maxLenBytes[1],
        NotificationAttributeId.DATE
        // Don't request action labels by default - they can cause parsing issues
    )
}

/**
 * Builds the PerformNotificationAction command packet (ANCS spec §3.11.3).
 *
 * Layout:
 *   [0]    CommandID = 0x02
 *   [1-4]  NotificationUID (uint32 little-endian)
 *   [5]    ActionID (0x00 = positive, 0x01 = negative)
 */
fun buildPerformNotificationActionCommand(uid: Int, actionId: Byte): ByteArray {
    val uidBytes = intToLe32(uid)

    return byteArrayOf(
        AncsCommandId.PERFORM_NOTIFICATION_ACTION,
        uidBytes[0], uidBytes[1], uidBytes[2], uidBytes[3],
        actionId
    )
}

// ---- helpers ----

fun intToLe32(v: Int): ByteArray = byteArrayOf(
    (v and 0xFF).toByte(),
    ((v shr 8) and 0xFF).toByte(),
    ((v shr 16) and 0xFF).toByte(),
    ((v shr 24) and 0xFF).toByte()
)

fun ushortToLe16(v: UShort): ByteArray = byteArrayOf(
    (v.toInt() and 0xFF).toByte(),
    ((v.toInt() shr 8) and 0xFF).toByte()
)

fun le32ToInt(bytes: ByteArray, offset: Int = 0): Int =
    (bytes[offset].toInt() and 0xFF) or
    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
    ((bytes[offset + 3].toInt() and 0xFF) shl 24)

fun le16ToInt(bytes: ByteArray, offset: Int = 0): Int =
    (bytes[offset].toInt() and 0xFF) or
    ((bytes[offset + 1].toInt() and 0xFF) shl 8)

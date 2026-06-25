package com.notimirror.ble

import com.notimirror.data.AncsCategoryId
import com.notimirror.data.AncsEventFlags
import com.notimirror.data.AncsEventId
import com.notimirror.data.AncsNotificationEvent

/**
 * Parses the 8-byte Notification Source packets from iOS.
 *
 * ANCS Notification Source packet layout (§3.5):
 *   Byte 0:   EventID       (0=Added, 1=Modified, 2=Removed)
 *   Byte 1:   EventFlags    (bitmask: Silent/Important/PreExisting/HasPositive/HasNegative)
 *   Byte 2:   CategoryID    (0=Other … 11=Entertainment)
 *   Byte 3:   CategoryCount (number of active notifications in this category)
 *   Bytes 4-7: NotificationUID (uint32 little-endian, stable within a Bluetooth session)
 */
fun parseNotificationSource(data: ByteArray): AncsNotificationEvent? {
    if (data.size < 8) return null

    val eventId = AncsEventId.from(data[0]) ?: return null
    val eventFlags = AncsEventFlags(data[1])
    val categoryId = AncsCategoryId.from(data[2])
    val categoryCount = data[3].toInt() and 0xFF
    val uid = le32ToInt(data, 4)

    return AncsNotificationEvent(eventId, eventFlags, categoryId, categoryCount, uid)
}

/**
 * Accumulator for multi-packet Data Source responses.
 *
 * iOS may fragment a GetNotificationAttributes response across several BLE packets.
 * This class buffers incoming bytes and attempts a parse after each append.
 *
 * GetNotificationAttributes response layout (§3.11.2 response):
 *   Byte 0:       CommandID (0x00 = GetNotificationAttributes)
 *   Bytes 1-4:    NotificationUID (uint32 LE)
 *   [Repeated per attribute]:
 *     Byte:       AttributeID
 *     2 Bytes LE: Length (uint16) — 0 if attribute not present
 *     N Bytes:    UTF-8 string (no null terminator)
 */
class DataSourceParser {
    private val buffer = mutableListOf<Byte>()

    /** Append a BLE packet fragment; returns a parsed result if complete, null if more data needed. */
    fun append(data: ByteArray): ParsedAttributes? {
        buffer.addAll(data.toList())
        return tryParse()
    }

    fun reset() { buffer.clear() }

    private fun tryParse(): ParsedAttributes? {
        val bytes = buffer.toByteArray()
        // Minimum: CommandID(1) + UID(4) + at least one attr header(3)
        if (bytes.size < 5) return null

        val commandId = bytes[0]
        if (commandId != AncsCommandId.GET_NOTIFICATION_ATTRIBUTES) {
            // Unrecognised command — discard buffer
            buffer.clear()
            return null
        }

        val uid = le32ToInt(bytes, 4)
        val attrs = mutableMapOf<Byte, String>()
        var pos = 5  // start of attribute list

        while (pos < bytes.size) {
            // Need at least attributeID(1) + length(2)
            if (pos + 3 > bytes.size) return null  // incomplete, wait for more

            val attrId = bytes[pos]
            val length = le16ToInt(bytes, pos + 1)
            pos += 3

            // Need `length` more bytes for the string value
            if (pos + length > bytes.size) return null  // incomplete

            val value = if (length > 0) {
                String(bytes, pos, length, Charsets.UTF_8)
            } else ""

            attrs[attrId] = value
            pos += length
        }

        // Successful parse — clear buffer for next response
        buffer.clear()

        return ParsedAttributes(
            uid = uid,
            appIdentifier = attrs[NotificationAttributeId.APP_IDENTIFIER] ?: "",
            title         = attrs[NotificationAttributeId.TITLE]          ?: "",
            subtitle      = attrs[NotificationAttributeId.SUBTITLE]       ?: "",
            message       = attrs[NotificationAttributeId.MESSAGE]        ?: "",
            date          = attrs[NotificationAttributeId.DATE]           ?: ""
        )
    }
}

data class ParsedAttributes(
    val uid: Int,
    val appIdentifier: String,
    val title: String,
    val subtitle: String,
    val message: String,
    val date: String
)

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
    private var lastPacketHash: String? = null
    private var lastPacketTime: Long = 0

    /** Append a BLE packet fragment; returns a parsed result if complete, null if more data needed. */
    @Synchronized
    fun append(data: ByteArray): ParsedAttributes? {
        // Create a hash of this packet to detect duplicates
        val packetHash = data.joinToString("") { "%02X".format(it) }
        val now = System.currentTimeMillis()

        // Skip duplicate packets that arrive within 50ms
        if (packetHash == lastPacketHash && (now - lastPacketTime) < 50) {
            android.util.Log.d("DataSourceParser", "Skipping duplicate packet")
            return null  // Skip duplicate
        }

        lastPacketHash = packetHash
        lastPacketTime = now

        // Check if this is the start of a new response
        if (data.isNotEmpty() && data[0] == AncsCommandId.GET_NOTIFICATION_ATTRIBUTES && buffer.isNotEmpty()) {
            // This is a new response starting, clear the old buffer
            android.util.Log.d("DataSourceParser", "New response detected, clearing buffer")
            buffer.clear()
        }

        val beforeSize = buffer.size
        buffer.addAll(data.toList())
        android.util.Log.d("DataSourceParser", "Buffer: $beforeSize -> ${buffer.size} bytes")

        return tryParse()
    }

    @Synchronized
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

        val uid = le32ToInt(bytes, 1)
        val attrs = mutableMapOf<Byte, String>()
        var pos = 5  // start of attribute list

        // Track expected attributes based on our request
        val expectedAttrs = setOf<Byte>(
            NotificationAttributeId.APP_IDENTIFIER,
            NotificationAttributeId.TITLE,
            NotificationAttributeId.SUBTITLE,
            NotificationAttributeId.MESSAGE,
            NotificationAttributeId.DATE
        )
        val foundAttrs = mutableSetOf<Byte>()

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
            foundAttrs.add(attrId)
            pos += length

            // Check if we've received all expected attributes
            if (expectedAttrs.all { it in foundAttrs }) {
                break
            }
        }

        // Successful parse — clear buffer for next response
        buffer.clear()

        return ParsedAttributes(
            uid = uid,
            appIdentifier = attrs[NotificationAttributeId.APP_IDENTIFIER] ?: "",
            title         = attrs[NotificationAttributeId.TITLE]          ?: "",
            subtitle      = attrs[NotificationAttributeId.SUBTITLE]       ?: "",
            message       = attrs[NotificationAttributeId.MESSAGE]        ?: "",
            date          = attrs[NotificationAttributeId.DATE]           ?: "",
            rawAttrs = attrs  // Add raw attrs for debugging
        )
    }
}

data class ParsedAttributes(
    val uid: Int,
    val appIdentifier: String,
    val title: String,
    val subtitle: String,
    val message: String,
    val date: String,
    val rawAttrs: Map<Byte, String> = emptyMap()  // For debugging
)

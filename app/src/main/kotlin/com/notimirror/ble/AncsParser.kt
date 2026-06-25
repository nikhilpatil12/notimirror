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
 * iOS may fragment responses across several BLE packets.
 * This class buffers incoming bytes and attempts a parse after each append.
 *
 * Handles two types of responses:
 * 1. GetNotificationAttributes (§3.11.2 response):
 *    Byte 0:       CommandID (0x00)
 *    Bytes 1-4:    NotificationUID (uint32 LE)
 *    [attributes...]
 *
 * 2. GetAppAttributes (§3.11.4 response):
 *    Byte 0:       CommandID (0x01)
 *    Bytes 1..n:   AppIdentifier (null-terminated UTF-8)
 *    [attributes...]
 */
class DataSourceParser {
    private val buffer = mutableListOf<Byte>()

    /** Append a BLE packet fragment; returns a parsed result if complete, null if more data needed. */
    @Synchronized
    fun append(data: ByteArray): DataSourceResponse? {
        buffer.addAll(data.toList())

        return tryParse()
    }

    @Synchronized
    fun reset() { buffer.clear() }

    private fun tryParse(): DataSourceResponse? {
        val bytes = buffer.toByteArray()
        if (bytes.isEmpty()) return null

        val commandId = bytes[0]
        return when (commandId) {
            AncsCommandId.GET_NOTIFICATION_ATTRIBUTES -> parseNotificationAttributes(bytes)
            AncsCommandId.GET_APP_ATTRIBUTES -> parseAppAttributes(bytes)
            else -> {
                android.util.Log.w("DataSourceParser", "Unknown command ID: $commandId")
                buffer.clear()
                null
            }
        }
    }

    private fun parseNotificationAttributes(bytes: ByteArray): ParsedNotificationAttributes? {
        // Minimum: CommandID(1) + UID(4) + at least one attr header(3)
        if (bytes.size < 5) return null

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
            if (pos + 3 > bytes.size) {
                return null  // incomplete, wait for more
            }

            val attrId = bytes[pos]
            val length = le16ToInt(bytes, pos + 1)
            pos += 3

            // Sanity check for length
            if (length > 5000) {
                android.util.Log.e("DataSourceParser", "Invalid attribute length: $length for attr $attrId, clearing buffer")
                buffer.clear()
                return null
            }

            // Need `length` more bytes for the string value
            if (pos + length > bytes.size) {
                return null  // incomplete
            }

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

        return ParsedNotificationAttributes(
            uid = uid,
            appIdentifier = attrs[NotificationAttributeId.APP_IDENTIFIER] ?: "",
            title         = attrs[NotificationAttributeId.TITLE]          ?: "",
            subtitle      = attrs[NotificationAttributeId.SUBTITLE]       ?: "",
            message       = attrs[NotificationAttributeId.MESSAGE]        ?: "",
            date          = attrs[NotificationAttributeId.DATE]           ?: "",
            rawAttrs = attrs
        )
    }

    private fun parseAppAttributes(bytes: ByteArray): ParsedAppAttributes? {
        // Minimum: CommandID(1) + AppID (at least 1 char + null) + attr header(3)
        if (bytes.size < 6) return null

        // Find the null terminator for the app identifier
        var nullPos = -1
        for (i in 1 until bytes.size) {
            if (bytes[i] == 0.toByte()) {
                nullPos = i
                break
            }
        }

        if (nullPos == -1 || nullPos == 1) {
            // No null terminator found or empty app ID
            return null
        }

        val appIdentifier = String(bytes, 1, nullPos - 1, Charsets.UTF_8)

        var pos = nullPos + 1  // Skip past null terminator
        val attrs = mutableMapOf<Byte, String>()

        while (pos < bytes.size) {
            // Need at least attributeID(1) + length(2)
            if (pos + 3 > bytes.size) {
                return null
            }

            val attrId = bytes[pos]
            val length = le16ToInt(bytes, pos + 1)
            pos += 3

            if (length > 1000) {
                android.util.Log.e("DataSourceParser", "Invalid app attr length: $length")
                buffer.clear()
                return null
            }

            if (pos + length > bytes.size) {
                return null
            }

            val value = if (length > 0) {
                String(bytes, pos, length, Charsets.UTF_8)
            } else ""

            attrs[attrId] = value
            pos += length
        }

        buffer.clear()

        val displayName = attrs[AppAttributeId.DISPLAY_NAME] ?: ""
        return ParsedAppAttributes(appIdentifier, displayName)
    }
}

// Sealed class for different Data Source responses
sealed class DataSourceResponse

data class ParsedNotificationAttributes(
    val uid: Int,
    val appIdentifier: String,
    val title: String,
    val subtitle: String,
    val message: String,
    val date: String,
    val rawAttrs: Map<Byte, String> = emptyMap()
) : DataSourceResponse()

data class ParsedAppAttributes(
    val appIdentifier: String,
    val displayName: String
) : DataSourceResponse()

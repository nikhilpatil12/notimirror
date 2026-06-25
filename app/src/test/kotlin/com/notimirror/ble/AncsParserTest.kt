package com.notimirror.ble

import com.notimirror.data.AncsCategoryId
import com.notimirror.data.AncsEventId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AncsParserTest {

    @Test
    fun parseNotificationSourceReadsUidFromOffsetFourLittleEndian() {
        val parsed = parseNotificationSource(
            byteArrayOf(
                0x00,
                0x12,
                0x04,
                0x07,
                0x78,
                0x56,
                0x34,
                0x12
            )
        )

        requireNotNull(parsed)
        assertEquals(AncsEventId.NotificationAdded, parsed.eventId)
        assertEquals(AncsCategoryId.Social, parsed.categoryId)
        assertEquals(7, parsed.categoryCount)
        assertEquals(0x12345678, parsed.notificationUid)
        assertTrue(parsed.eventFlags.isImportant)
        assertTrue(parsed.eventFlags.hasNegativeAction)
    }

    @Test
    fun parseNotificationSourceRejectsShortPackets() {
        assertNull(parseNotificationSource(byteArrayOf(0x00, 0x01, 0x02)))
    }

    @Test
    fun dataSourceParserReassemblesFragmentedNotificationAttributes() {
        val uid = 0x01020304
        val response = byteArrayOf(AncsCommandId.GET_NOTIFICATION_ATTRIBUTES) +
            intToLe32(uid) +
            attr(NotificationAttributeId.APP_IDENTIFIER, "com.apple.MobileSMS") +
            attr(NotificationAttributeId.TITLE, "Nikhil") +
            attr(NotificationAttributeId.SUBTITLE, "") +
            attr(NotificationAttributeId.MESSAGE, "Hello") +
            attr(NotificationAttributeId.DATE, "20260625T120000")

        val parser = DataSourceParser()
        assertNull(parser.append(response.copyOfRange(0, 9)))
        assertNull(parser.append(response.copyOfRange(9, 24)))
        val parsed = parser.append(response.copyOfRange(24, response.size))

        require(parsed is ParsedNotificationAttributes)
        assertEquals(uid, parsed.uid)
        assertEquals("com.apple.MobileSMS", parsed.appIdentifier)
        assertEquals("Nikhil", parsed.title)
        assertEquals("", parsed.subtitle)
        assertEquals("Hello", parsed.message)
        assertEquals("20260625T120000", parsed.date)
    }

    @Test
    fun dataSourceParserParsesAppAttributes() {
        val response = byteArrayOf(AncsCommandId.GET_APP_ATTRIBUTES) +
            "com.apple.MobileSMS".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x00) +
            attr(AppAttributeId.DISPLAY_NAME, "Messages")

        val parsed = DataSourceParser().append(response)

        require(parsed is ParsedAppAttributes)
        assertEquals("com.apple.MobileSMS", parsed.appIdentifier)
        assertEquals("Messages", parsed.displayName)
    }

    @Test
    fun buildGetNotificationAttributesCommandEncodesUidAndRequestedAttributes() {
        val command = buildGetNotificationAttributesCommand(0x01020304, maxLength = 100u)

        assertArrayEquals(
            byteArrayOf(
                AncsCommandId.GET_NOTIFICATION_ATTRIBUTES,
                0x04,
                0x03,
                0x02,
                0x01,
                NotificationAttributeId.APP_IDENTIFIER,
                NotificationAttributeId.TITLE,
                0x64,
                0x00,
                NotificationAttributeId.SUBTITLE,
                0x64,
                0x00,
                NotificationAttributeId.MESSAGE,
                0x64,
                0x00,
                NotificationAttributeId.DATE
            ),
            command
        )
    }

    private fun attr(id: Byte, value: String): ByteArray {
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf(
            id,
            (valueBytes.size and 0xFF).toByte(),
            ((valueBytes.size shr 8) and 0xFF).toByte()
        ) + valueBytes
    }
}

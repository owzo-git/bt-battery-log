package com.owzo.btbatteryntfy.monitor.shokz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShokzProtocolTest {

    @Test
    fun requestFramesAreWellFormed() {
        val allFrames = ShokzProtocol.INIT_FRAMES + ShokzProtocol.REFRESH_FRAMES.map { it.second }
        for (frame in allFrames) {
            assertEquals(0xA5.toByte(), frame[0])
            assertEquals(0x5A.toByte(), frame[1])
            val bodyLength = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
            assertEquals(frame.size, ShokzFrameParser.HEADER_LENGTH + bodyLength)
        }
        for ((opcode, frame) in ShokzProtocol.REFRESH_FRAMES) {
            assertEquals(opcode, frame[ShokzFrameParser.OPCODE_OFFSET].toInt() and 0xFF)
        }
    }

    @Test
    fun requestFramesAreNotParsedAsResponses() {
        val parser = ShokzFrameParser()
        val responses = parser.feed(ShokzProtocol.REFRESH_FRAMES.first().second)
        assertTrue(responses.isEmpty())
        assertEquals(1, parser.ignoredFrameCount)
        assertEquals(0, parser.pendingByteCount)
    }

    @Test
    fun parsesSingleResponse() {
        val responses = ShokzFrameParser().feed(responseFrame(0x0B, byteArrayOf(0x00, 60)))
        assertEquals(listOf(ShokzResponse(0x0B, byteArrayOf(0x00, 60))), responses)
    }

    @Test
    fun parsesFrameSplitAcrossReads() {
        val frame = responseFrame(0x03, byteArrayOf(0x00, 7, 7))
        val parser = ShokzFrameParser()

        // Split inside the header magic, inside the length, and inside the body.
        assertTrue(parser.feed(frame.copyOfRange(0, 1)).isEmpty())
        assertTrue(parser.feed(frame.copyOfRange(1, 3)).isEmpty())
        assertTrue(parser.feed(frame.copyOfRange(3, 30)).isEmpty())
        val responses = parser.feed(frame.copyOfRange(30, frame.size))

        assertEquals(listOf(ShokzResponse(0x03, byteArrayOf(0x00, 7, 7))), responses)
        assertEquals(0, parser.pendingByteCount)
        assertEquals(0, parser.discardedByteCount)
    }

    @Test
    fun parsesConcatenatedFramesAndLeadingGarbage() {
        val garbage = byteArrayOf(0x01, 0xA5.toByte(), 0x00, 0x5A)
        val joined = garbage +
            responseFrame(0x03, byteArrayOf(0x00, 7, 8)) +
            ShokzProtocol.REFRESH_FRAMES.first().second +
            responseFrame(0x0B, byteArrayOf(0x00, 60))
        val parser = ShokzFrameParser()

        val responses = parser.feed(joined)

        assertEquals(listOf(0x03, 0x0B), responses.map { it.opcode })
        assertEquals(garbage.size, parser.discardedByteCount)
        assertEquals(1, parser.ignoredFrameCount)
        assertEquals(0, parser.pendingByteCount)
    }

    @Test
    fun keepsTruncatedFrameUntilRestArrives() {
        val frame = responseFrame(0x0B, byteArrayOf(0x00, 60))
        val parser = ShokzFrameParser()

        assertTrue(parser.feed(frame.copyOfRange(0, frame.size - 1)).isEmpty())
        assertEquals(frame.size - 1, parser.pendingByteCount)
        assertEquals(1, parser.feed(frame.copyOfRange(frame.size - 1, frame.size)).size)
    }

    @Test
    fun resyncsPastImplausibleLength() {
        val bogusHeader = byteArrayOf(0xA5.toByte(), 0x5A, 0xFF.toByte(), 0xFF.toByte())
        val parser = ShokzFrameParser()

        val responses = parser.feed(bogusHeader + responseFrame(0x0B, byteArrayOf(0x00, 55)))

        assertEquals(listOf(0x0B), responses.map { it.opcode })
    }

    @Test
    fun dropsFrameWhosePayloadOverrunsIt() {
        val frame = responseFrame(0x0B, byteArrayOf(0x00, 60))
        frame[ShokzFrameParser.PAYLOAD_LENGTH_OFFSET] = 40
        val parser = ShokzFrameParser()

        assertTrue(parser.feed(frame).isEmpty())
        assertEquals(1, parser.ignoredFrameCount)
    }

    @Test
    fun dropsTooShortFrame() {
        val shortFrame = byteArrayOf(0xA5.toByte(), 0x5A, 4, 0, 0, 0, 0, 0, 1, 2, 3, 4)
        val parser = ShokzFrameParser()

        assertTrue(parser.feed(shortFrame).isEmpty())
        assertEquals(1, parser.ignoredFrameCount)
        assertEquals(0, parser.pendingByteCount)
    }

    @Test
    fun budLevelsConvertToPercent() {
        assertEquals(10, ShokzProtocol.budRawToPercent(0))
        assertEquals(80, ShokzProtocol.budRawToPercent(7))
        assertEquals(90, ShokzProtocol.budRawToPercent(8))
        assertEquals(100, ShokzProtocol.budRawToPercent(9))
        assertNull(ShokzProtocol.budRawToPercent(10))
        assertNull(ShokzProtocol.budRawToPercent(-1))
        assertNull(ShokzProtocol.budRawToPercent(null))
    }

    @Test
    fun casePercentIsRangeChecked() {
        assertEquals(0, ShokzProtocol.caseRawToPercent(0))
        assertEquals(100, ShokzProtocol.caseRawToPercent(100))
        assertNull(ShokzProtocol.caseRawToPercent(101))
        assertNull(ShokzProtocol.caseRawToPercent(255))
    }

    @Test
    fun decodesExpectedOfficialAppReading() {
        // Official app showed L 80% / R 80% / Case 60%.
        val status = ShokzProtocol.decodeBattery(
            mapOf(
                0x03 to byteArrayOf(0x00, 7, 7),
                0x0B to byteArrayOf(0x00, 60),
                0x02 to byteArrayOf(0x01, 0x02),
            ),
            sampledAtMs = 1234L,
        )!!

        assertEquals(80, status.left)
        assertEquals(80, status.right)
        assertEquals(60, status.case)
        assertEquals(1234L, status.sampledAtMs)
    }

    @Test
    fun decodesPayloadsCapturedFromOpenFit2Plus() {
        // Captured on device; official app showed L 100% / R 100% / Case 60%.
        val responses = ShokzFrameParser().feed(
            responseFrame(0x03, ShokzProtocol.hexToBytes("00090900")) +
                responseFrame(0x0B, ShokzProtocol.hexToBytes("003c0000")),
        )
        val status = ShokzProtocol.decodeBattery(responses.associate { it.opcode to it.payload }, 0L)!!

        assertEquals(100, status.left)
        assertEquals(100, status.right)
        assertEquals(60, status.case)
    }

    @Test
    fun missingComponentsStayUnknown() {
        val budsOnly = ShokzProtocol.decodeBattery(mapOf(0x03 to byteArrayOf(0x00, 9, 8)), 0L)!!
        assertEquals(100, budsOnly.left)
        assertEquals(90, budsOnly.right)
        assertNull(budsOnly.case)

        val caseOnly = ShokzProtocol.decodeBattery(mapOf(0x0B to byteArrayOf(0x00, 0x50)), 0L)!!
        assertNull(caseOnly.left)
        assertNull(caseOnly.right)
        assertEquals(80, caseOnly.case)

        val leftOnly = ShokzProtocol.decodeBattery(mapOf(0x03 to byteArrayOf(0x00, 4)), 0L)!!
        assertEquals(50, leftOnly.left)
        assertNull(leftOnly.right)
    }

    @Test
    fun outOfRangeValuesAreNotFabricated() {
        val status = ShokzProtocol.decodeBattery(
            mapOf(0x03 to byteArrayOf(0x00, 12, 0xFF.toByte()), 0x0B to byteArrayOf(0x00, 0xC8.toByte())),
            0L,
        )
        assertNull(status)
    }

    @Test
    fun noBatteryOpcodesDecodeToNull() {
        assertNull(ShokzProtocol.decodeBattery(emptyMap(), 0L))
        assertNull(ShokzProtocol.decodeBattery(mapOf(0x03 to byteArrayOf(0x00), 0x0B to byteArrayOf()), 0L))
    }

    @Test
    fun matchesOpenFitNamesOnly() {
        assertTrue(ShokzProtocol.isSupportedDeviceName("OpenFit 2+ by Shokz"))
        assertTrue(ShokzProtocol.isSupportedDeviceName("OPENFIT Air"))
        assertFalse(ShokzProtocol.isSupportedDeviceName("OpenRun Pro"))
        assertFalse(ShokzProtocol.isSupportedDeviceName(null))
    }

    /** Builds a response frame with the layout the upstream parser expects. */
    private fun responseFrame(opcode: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(ShokzFrameParser.PAYLOAD_OFFSET + payload.size)
        val bodyLength = frame.size - ShokzFrameParser.HEADER_LENGTH
        frame[0] = 0xA5.toByte()
        frame[1] = 0x5A
        frame[2] = (bodyLength and 0xFF).toByte()
        frame[3] = (bodyLength shr 8).toByte()
        frame[ShokzFrameParser.OPCODE_OFFSET] = opcode.toByte()
        frame[ShokzFrameParser.RESPONSE_FLAG_OFFSET] = 0x80.toByte()
        frame[ShokzFrameParser.PAYLOAD_LENGTH_OFFSET] = payload.size.toByte()
        payload.copyInto(frame, ShokzFrameParser.PAYLOAD_OFFSET)
        return frame
    }
}

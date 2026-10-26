package com.owzo.btbatteryntfy.monitor.shokz

import java.util.Locale

/*
 * Shokz OpenFit vendor protocol (RFCOMM, SDP UUID 0000fef0-...).
 *
 * Request frames, opcode mapping, response layout and bud level conversion are ported from
 * shokz-openfit-battery-sdk by Yentl Hendrickx (MIT License):
 * https://github.com/YentlHendrickx/shokz-openfit-battery-sdk/blob/main/shokz_sdk.py
 * See THIRD_PARTY_NOTICES.md for the full license text.
 *
 * Frames carry a rolling counter and what appears to be a checksum, so they are replayed verbatim
 * rather than synthesized.
 */

data class ShokzResponse(
    val opcode: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        return other is ShokzResponse && opcode == other.opcode && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * opcode + payload.contentHashCode()
}

data class ShokzBatteryStatus(
    val left: Int?,
    val right: Int?,
    val case: Int?,
    val leftRaw: Int?,
    val rightRaw: Int?,
    val caseRaw: Int?,
    val sampledAtMs: Long,
) {
    val hasAnyValue: Boolean
        get() = left != null || right != null || case != null
}

object ShokzProtocol {
    const val SERVICE_UUID = "0000fef0-0000-1000-8000-00805f9b34fb"

    const val OPCODE_BUDS = 0x03
    const val OPCODE_CASE = 0x0B

    val INIT_FRAMES: List<ByteArray> = listOf(
        "a55a2c000101000028006cfd01000000200000000200000004000000020000000300000004000000950000000400000000000000",
        "a55a3000010000002c00bf290100000024000000020000000400000001000000030000000400000031000000040000000400000000000000",
    ).map(::hexToBytes)

    /** Normal polling sequence of the official app, in send order. */
    val REFRESH_FRAMES: List<Pair<Int, ByteArray>> = listOf(
        0x02 to "a55a2c000100000028005ab701000000200000000200000004000000020000000300000004000000020000000400000000000000",
        0x03 to "a55a2c00010000002800a77401000000200000000200000004000000020000000300000004000000030000000400000000000000",
        0x07 to "a55a2c0001000000280056bb01000000200000000200000004000000020000000300000004000000070000000400000000000000",
        0x08 to "a55a2c0001000000280042af01000000200000000200000004000000020000000300000004000000080000000400000000000000",
        0x0B to "a55a2c0001000000280046ab010000002000000002000000040000000200000003000000040000000b0000000400000000000000",
        0x10 to "a55a2c00010000002800628f01000000200000000200000004000000020000000300000004000000100000000400000000000000",
        0x2B to "a55a2c00010000002800c714010000002000000002000000040000000200000003000000040000002b0000000400000000000000",
    ).map { (opcode, hex) -> opcode to hexToBytes(hex) }

    fun isSupportedDeviceName(name: String?): Boolean {
        return name?.lowercase(Locale.US)?.contains("openfit") == true
    }

    /** Buds report a coarse 0..9 level: 9 is full, anything below is ten-percent steps. */
    fun budRawToPercent(raw: Int?): Int? {
        if (raw == null || raw !in 0..9) {
            return null
        }
        return if (raw == 9) 100 else raw * 10 + 10
    }

    fun caseRawToPercent(raw: Int?): Int? = raw?.takeIf { it in 0..100 }

    /** Returns null when neither battery opcode produced a usable value. */
    fun decodeBattery(responses: Map<Int, ByteArray>, sampledAtMs: Long): ShokzBatteryStatus? {
        val buds = responses[OPCODE_BUDS]
        val case = responses[OPCODE_CASE]
        val leftRaw = buds?.getOrNull(1)?.toUnsigned()
        val rightRaw = buds?.getOrNull(2)?.toUnsigned()
        val caseRaw = case?.getOrNull(1)?.toUnsigned()

        val status = ShokzBatteryStatus(
            left = budRawToPercent(leftRaw),
            right = budRawToPercent(rightRaw),
            case = caseRawToPercent(caseRaw),
            leftRaw = leftRaw,
            rightRaw = rightRaw,
            caseRaw = caseRaw,
            sampledAtMs = sampledAtMs,
        )
        return status.takeIf { it.hasAnyValue }
    }

    internal fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd hex length" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun Byte.toUnsigned(): Int = toInt() and 0xFF
}

/**
 * Incremental frame parser. RFCOMM stream reads may split a frame or join several, so bytes are
 * buffered until a whole frame (`A5 5A`, u16 LE body length, 6 more header bytes, body) is present.
 */
class ShokzFrameParser {
    private var buffer = ByteArray(0)

    /** Number of complete frames that were not decodable battery/status responses. */
    var ignoredFrameCount: Int = 0
        private set

    /** Bytes discarded while searching for a frame header. */
    var discardedByteCount: Int = 0
        private set

    val pendingByteCount: Int
        get() = buffer.size

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<ShokzResponse> {
        if (length > 0) {
            buffer += bytes.copyOfRange(0, length)
        }

        val responses = mutableListOf<ShokzResponse>()
        var index = 0
        while (true) {
            val headerAt = findHeader(index)
            if (headerAt < 0) {
                // Keep a trailing 0xA5 that may be the first half of a split header.
                val keepFrom = if (buffer.size > index && buffer.last() == HEADER_0) buffer.size - 1 else buffer.size
                discardedByteCount += keepFrom - index
                index = keepFrom
                break
            }
            discardedByteCount += headerAt - index
            index = headerAt

            if (buffer.size - index < 4) {
                break
            }
            val bodyLength = (buffer[index + 2].toInt() and 0xFF) or ((buffer[index + 3].toInt() and 0xFF) shl 8)
            if (bodyLength > MAX_BODY_LENGTH) {
                // Not a plausible frame; resync past this header.
                discardedByteCount += 2
                index += 2
                continue
            }

            val frameEnd = index + HEADER_LENGTH + bodyLength
            if (frameEnd > buffer.size) {
                break
            }

            val response = decodeFrame(buffer, index, frameEnd)
            if (response != null) {
                responses += response
            } else {
                ignoredFrameCount += 1
            }
            index = frameEnd
        }

        buffer = buffer.copyOfRange(index, buffer.size)
        return responses
    }

    private fun findHeader(from: Int): Int {
        var i = from
        while (i + 1 < buffer.size) {
            if (buffer[i] == HEADER_0 && buffer[i + 1] == HEADER_1) {
                return i
            }
            i += 1
        }
        return -1
    }

    private fun decodeFrame(data: ByteArray, start: Int, end: Int): ShokzResponse? {
        val frameLength = end - start
        if (frameLength < PAYLOAD_OFFSET) {
            return null
        }
        if (data[start + RESPONSE_FLAG_OFFSET] != RESPONSE_FLAG) {
            return null
        }
        val opcode = data[start + OPCODE_OFFSET].toInt() and 0xFF
        val payloadLength = data[start + PAYLOAD_LENGTH_OFFSET].toInt() and 0xFF
        if (PAYLOAD_OFFSET + payloadLength > frameLength) {
            return null
        }
        val payloadStart = start + PAYLOAD_OFFSET
        return ShokzResponse(opcode, data.copyOfRange(payloadStart, payloadStart + payloadLength))
    }

    companion object {
        private const val HEADER_0 = 0xA5.toByte()
        private const val HEADER_1 = 0x5A.toByte()
        private const val RESPONSE_FLAG = 0x80.toByte()

        const val HEADER_LENGTH = 8
        const val OPCODE_OFFSET = 40
        const val RESPONSE_FLAG_OFFSET = 41
        const val PAYLOAD_LENGTH_OFFSET = 48
        const val PAYLOAD_OFFSET = 52

        /** Observed frames are well under 100 bytes; anything this large is a false header match. */
        const val MAX_BODY_LENGTH = 1024
    }
}

package com.owzo.btbatteryntfy.monitor.shokz

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import com.owzo.btbatteryntfy.data.AppPreferences
import com.owzo.btbatteryntfy.monitor.BatteryMonitorEngine
import com.owzo.btbatteryntfy.monitor.BluetoothBatteryProbe
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ShokzReadFailure(val label: String) {
    NOT_APPLICABLE("not an OpenFit device"),
    NOT_CONNECTED("not connected"),
    NO_PERMISSION("Bluetooth permission missing"),
    BLUETOOTH_OFF("Bluetooth is off"),
    CONNECT_FAILED("RFCOMM connect failed (official Shokz app may hold the channel)"),
    TIMEOUT("timed out"),
    CONNECTION_LOST("connection lost during read"),
    NO_RESPONSE("no reply to requests"),
    NO_BATTERY_DATA("no usable battery frames"),
}

data class ShokzReadDiagnostics(
    val bytesReceived: Int = 0,
    val opcodesSeen: List<Int> = emptyList(),
    val ignoredFrames: Int = 0,
    val discardedBytes: Int = 0,
    /** Battery payloads only (opcodes 0x03/0x0B); they carry no identifiers. */
    val batteryPayloadHex: Map<Int, String> = emptyMap(),
) {
    fun describe(): String = buildString {
        append("rx=${bytesReceived}B")
        append(" ops=")
        append(opcodesSeen.joinToString(",") { "0x%02X".format(it) }.ifEmpty { "-" })
        if (ignoredFrames > 0) append(" ignored=$ignoredFrames")
        if (discardedBytes > 0) append(" junk=${discardedBytes}B")
        batteryPayloadHex.forEach { (opcode, hex) -> append(" 0x%02X=%s".format(opcode, hex)) }
    }
}

sealed interface ShokzReadResult {
    val elapsedMs: Long

    data class Success(
        val status: ShokzBatteryStatus,
        override val elapsedMs: Long,
        val diagnostics: ShokzReadDiagnostics,
    ) : ShokzReadResult

    data class Failure(
        val failure: ShokzReadFailure,
        override val elapsedMs: Long,
        val detail: String? = null,
        val diagnostics: ShokzReadDiagnostics? = null,
    ) : ShokzReadResult
}

/**
 * Reads left/right/case battery from a connected Shokz OpenFit over the vendor RFCOMM service.
 * Each read opens a short-lived socket (connect -> init -> poll -> close); nothing stays connected.
 */
object ShokzOpenFitBatteryProvider {
    private val SERVICE_UUID: UUID = UUID.fromString(ShokzProtocol.SERVICE_UUID)

    private const val CONNECT_TIMEOUT_MS = 6_000L
    private const val TOTAL_TIMEOUT_MS = 15_000L
    private const val INIT_READ_TIMEOUT_MS = 800L
    private const val REFRESH_READ_TIMEOUT_MS = 800L
    private const val QUIET_AFTER_DATA_MS = 50L
    private const val POLL_INTERVAL_MS = 10L

    private val deviceLocks = ConcurrentHashMap<String, Mutex>()

    /** Bonded OpenFit devices that look connected right now. Never pages a disconnected device. */
    fun findConnectedDevices(context: Context): List<BluetoothDevice> {
        if (!BatteryMonitorEngine.hasBluetoothConnectPermission(context)) {
            return emptyList()
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        val bonded = try {
            if (!adapter.isEnabled) return emptyList()
            adapter.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            return emptyList()
        }

        val appPreferences = AppPreferences(context)
        return bonded.filter { device ->
            ShokzProtocol.isSupportedDeviceName(BatteryMonitorEngine.safeDeviceName(device)) &&
                isConnected(device, appPreferences)
        }
    }

    suspend fun read(context: Context, device: BluetoothDevice): ShokzReadResult = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - startedAt

        if (!BatteryMonitorEngine.hasBluetoothConnectPermission(context)) {
            return@withContext ShokzReadResult.Failure(ShokzReadFailure.NO_PERMISSION, elapsed())
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            return@withContext ShokzReadResult.Failure(ShokzReadFailure.BLUETOOTH_OFF, elapsed())
        }
        if (!ShokzProtocol.isSupportedDeviceName(BatteryMonitorEngine.safeDeviceName(device))) {
            return@withContext ShokzReadResult.Failure(ShokzReadFailure.NOT_APPLICABLE, elapsed())
        }
        if (!isConnected(device, AppPreferences(context))) {
            return@withContext ShokzReadResult.Failure(ShokzReadFailure.NOT_CONNECTED, elapsed())
        }

        // The vendor channel accepts one client at a time; never race ourselves for it.
        val lock = deviceLocks.getOrPut(device.address) { Mutex() }
        lock.withLock { readLocked(device, startedAt) }
    }

    private suspend fun readLocked(device: BluetoothDevice, startedAt: Long): ShokzReadResult {
        fun elapsed() = SystemClock.elapsedRealtime() - startedAt

        val socket = try {
            device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        } catch (_: SecurityException) {
            return ShokzReadResult.Failure(ShokzReadFailure.NO_PERMISSION, elapsed())
        } catch (e: IOException) {
            return ShokzReadResult.Failure(ShokzReadFailure.CONNECT_FAILED, elapsed(), e.message)
        }

        // BluetoothSocket has no connect/read timeouts; closing it from a watchdog unblocks both.
        val timedOut = AtomicBoolean(false)
        return try {
            withSocketWatchdog(socket, TOTAL_TIMEOUT_MS, timedOut) {
                val connectError = withSocketWatchdog(socket, CONNECT_TIMEOUT_MS, timedOut) {
                    try {
                        socket.connect()
                        null
                    } catch (e: IOException) {
                        e
                    } catch (_: SecurityException) {
                        IOException("SecurityException")
                    }
                }
                if (connectError != null) {
                    val failure = if (timedOut.get()) ShokzReadFailure.TIMEOUT else ShokzReadFailure.CONNECT_FAILED
                    return@withSocketWatchdog ShokzReadResult.Failure(failure, elapsed(), connectError.message)
                }
                exchange(socket, startedAt, timedOut)
            }
        } finally {
            closeQuietly(socket)
        }
    }

    private suspend fun exchange(
        socket: BluetoothSocket,
        startedAt: Long,
        timedOut: AtomicBoolean,
    ): ShokzReadResult {
        fun elapsed() = SystemClock.elapsedRealtime() - startedAt

        val parser = ShokzFrameParser()
        val responses = LinkedHashMap<Int, ByteArray>()
        var bytesReceived = 0

        fun diagnostics() = ShokzReadDiagnostics(
            bytesReceived = bytesReceived,
            opcodesSeen = responses.keys.toList(),
            ignoredFrames = parser.ignoredFrameCount,
            discardedBytes = parser.discardedByteCount,
            batteryPayloadHex = responses
                .filterKeys { it == ShokzProtocol.OPCODE_BUDS || it == ShokzProtocol.OPCODE_CASE }
                .mapValues { (_, payload) -> payload.toHex() },
        )

        try {
            val input = socket.inputStream
            val output = socket.outputStream
            val readBuffer = ByteArray(1024)

            suspend fun request(frame: ByteArray, expectedOpcode: Int?, timeoutMs: Long) {
                output.write(frame)
                output.flush()
                bytesReceived += readUntil(input, readBuffer, parser, responses, expectedOpcode, timeoutMs)
            }

            for (frame in ShokzProtocol.INIT_FRAMES) {
                request(frame, expectedOpcode = null, timeoutMs = INIT_READ_TIMEOUT_MS)
            }
            for ((opcode, frame) in ShokzProtocol.REFRESH_FRAMES) {
                request(frame, expectedOpcode = opcode, timeoutMs = REFRESH_READ_TIMEOUT_MS)
            }
        } catch (e: IOException) {
            val failure = if (timedOut.get()) ShokzReadFailure.TIMEOUT else ShokzReadFailure.CONNECTION_LOST
            // Keep anything decoded before the failure; a partial read is still useful.
            val partial = ShokzProtocol.decodeBattery(responses, System.currentTimeMillis())
            return if (partial != null) {
                ShokzReadResult.Success(partial, elapsed(), diagnostics())
            } else {
                ShokzReadResult.Failure(failure, elapsed(), e.message, diagnostics())
            }
        }

        if (bytesReceived == 0) {
            return ShokzReadResult.Failure(ShokzReadFailure.NO_RESPONSE, elapsed(), diagnostics = diagnostics())
        }
        val status = ShokzProtocol.decodeBattery(responses, System.currentTimeMillis())
            ?: return ShokzReadResult.Failure(ShokzReadFailure.NO_BATTERY_DATA, elapsed(), diagnostics = diagnostics())
        return ShokzReadResult.Success(status, elapsed(), diagnostics())
    }

    /**
     * Reads without blocking (polls [InputStream.available]) until [expectedOpcode] arrives or the
     * window closes. With no expected opcode, it mirrors upstream: stop shortly after data goes quiet.
     */
    private suspend fun readUntil(
        input: InputStream,
        readBuffer: ByteArray,
        parser: ShokzFrameParser,
        responses: MutableMap<Int, ByteArray>,
        expectedOpcode: Int?,
        timeoutMs: Long,
    ): Int {
        var received = 0
        var deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val available = input.available()
            if (available <= 0) {
                delay(POLL_INTERVAL_MS)
                continue
            }

            val count = input.read(readBuffer, 0, minOf(available, readBuffer.size))
            if (count < 0) {
                throw IOException("EOF")
            }
            received += count
            var sawExpected = false
            for (response in parser.feed(readBuffer, count)) {
                responses[response.opcode] = response.payload
                if (response.opcode == expectedOpcode) {
                    sawExpected = true
                }
            }
            if (sawExpected && parser.pendingByteCount == 0) {
                break
            }
            if (expectedOpcode == null) {
                deadline = SystemClock.elapsedRealtime() + QUIET_AFTER_DATA_MS
            }
        }
        return received
    }

    private suspend fun <T> withSocketWatchdog(
        socket: BluetoothSocket,
        timeoutMs: Long,
        timedOut: AtomicBoolean,
        block: suspend () -> T,
    ): T = coroutineScope {
        // Runs on another IO thread while block() sits in a blocking socket call.
        val watchdog = launch {
            delay(timeoutMs)
            timedOut.set(true)
            closeQuietly(socket)
        }
        try {
            block()
        } finally {
            watchdog.cancel()
        }
    }

    private fun isConnected(device: BluetoothDevice, appPreferences: AppPreferences): Boolean {
        readIsConnected(device)?.let { return it }
        // Android only reports a battery level for a connected device.
        if (BluetoothBatteryProbe.readBatteryLevel(device) != null) {
            return true
        }
        val identity = BatteryMonitorEngine.stableDeviceIdentity(
            BatteryMonitorEngine.safeDeviceName(device),
            device.address,
        )
        return appPreferences.getTrackedDeviceState(identity).isAudioSessionActive
    }

    private fun readIsConnected(device: BluetoothDevice): Boolean? {
        return try {
            BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun closeQuietly(socket: BluetoothSocket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

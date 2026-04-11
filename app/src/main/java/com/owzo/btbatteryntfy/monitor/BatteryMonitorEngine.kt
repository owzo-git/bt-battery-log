package com.owzo.btbatteryntfy.monitor

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.owzo.btbatteryntfy.data.AppPreferences
import com.owzo.btbatteryntfy.data.NotificationReservation
import com.owzo.btbatteryntfy.net.NtfyClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BatterySample(
    val deviceName: String?,
    val deviceAddress: String?,
    val batteryPercent: Int,
    val observedAtMs: Long,
    val source: String,
)

object BatteryMonitorEngine {
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

    fun hasBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun safeDeviceName(device: BluetoothDevice?): String? {
        if (device == null) {
            return null
        }

        return try {
            device.name?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }

    fun processSample(context: Context, sample: BatterySample) {
        if (sample.batteryPercent !in 0..100) {
            return
        }

        val appPreferences = AppPreferences(context)
        val settings = appPreferences.loadSettings()
        if (!settings.monitoringEnabled) {
            return
        }

        val deviceLabel = displayName(sample.deviceName, sample.deviceAddress)
        val timestamp = formatTimestamp(sample.observedAtMs)
        appPreferences.updateLastBatteryEvent(
            "Last battery event: $deviceLabel ${sample.batteryPercent}% at $timestamp",
        )

        val identity = stableDeviceIdentity(sample.deviceName, sample.deviceAddress)
        if (sample.batteryPercent > settings.resetThreshold) {
            if (appPreferences.clearNotificationLatchIfRecovered(identity)) {
                appPreferences.appendLog(
                    "${shortTimestamp(sample.observedAtMs)} $deviceLabel recovered to ${sample.batteryPercent}% and notifications were re-armed.",
                )
            }
            return
        }

        if (sample.batteryPercent > settings.lowThreshold) {
            return
        }

        val endpoint = NtfyClient.resolveEndpoint(settings.serverUrlOrEndpoint, settings.topic)
        if (endpoint == null) {
            appPreferences.updateLastSendResult("Last ntfy send result: invalid ntfy URL or topic")
            appPreferences.appendLog(
                "${shortTimestamp(sample.observedAtMs)} Low battery for $deviceLabel (${sample.batteryPercent}%) but ntfy is not configured correctly.",
            )
            return
        }

        when (val reservation = appPreferences.reserveNotificationIfAllowed(identity, settings.cooldownMillis, sample.observedAtMs)) {
            NotificationReservation.AlreadyNotified -> {
                appPreferences.appendLog(
                    "${shortTimestamp(sample.observedAtMs)} Skipped duplicate low-battery send for $deviceLabel at ${sample.batteryPercent}%.",
                )
            }

            is NotificationReservation.CooldownActive -> {
                appPreferences.appendLog(
                    "${shortTimestamp(sample.observedAtMs)} Cooldown blocked a low-battery send for $deviceLabel until ${formatTimestamp(reservation.nextAllowedAtMs)}.",
                )
            }

            NotificationReservation.Reserved -> {
                val result = NtfyClient.send(
                    endpoint = endpoint,
                    authorizationHeader = settings.authorizationHeader,
                    title = "BT battery low: $deviceLabel ${sample.batteryPercent}%",
                    message = buildLowBatteryMessage(sample),
                )

                if (result.success) {
                    appPreferences.markNotificationSent(identity, sample.batteryPercent, sample.observedAtMs)
                    appPreferences.updateLastSendResult(
                        "Last ntfy send result: sent for $deviceLabel at ${sample.batteryPercent}%",
                    )
                    appPreferences.appendLog(
                        "${shortTimestamp(sample.observedAtMs)} ntfy sent for $deviceLabel at ${sample.batteryPercent}% from ${sample.source}.",
                    )
                } else {
                    appPreferences.updateLastSendResult(
                        "Last ntfy send result: ${result.details}",
                    )
                    appPreferences.appendLog(
                        "${shortTimestamp(sample.observedAtMs)} ntfy failed for $deviceLabel at ${sample.batteryPercent}%: ${result.details}.",
                    )
                }
            }
        }
    }

    fun displayName(deviceName: String?, deviceAddress: String?): String {
        return deviceName?.takeIf { it.isNotBlank() } ?: deviceAddress ?: "Unknown device"
    }

    fun stableDeviceIdentity(deviceName: String?, deviceAddress: String?): String {
        return deviceAddress?.takeIf { it.isNotBlank() }
            ?: "fallback:${deviceName?.trim()?.lowercase(Locale.US) ?: "unknown"}"
    }

    fun formatTimestamp(timestampMs: Long): String =
        timestampFormatter.format(Instant.ofEpochMilli(timestampMs))

    private fun shortTimestamp(timestampMs: Long): String =
        formatTimestamp(timestampMs).substringAfter('T').substringBeforeLast(':')

    private fun buildLowBatteryMessage(sample: BatterySample): String {
        val deviceLabel = displayName(sample.deviceName, sample.deviceAddress)
        val lines = mutableListOf<String>()
        lines += "Bluetooth device battery low"
        lines += ""
        lines += "Device: $deviceLabel"
        lines += "Address: ${sample.deviceAddress ?: "Unavailable"}"
        lines += "Battery: ${sample.batteryPercent}%"
        lines += "Timestamp: ${formatTimestamp(sample.observedAtMs)}"
        lines += "Source: ${sample.source}"
        return lines.joinToString(separator = "\n")
    }
}


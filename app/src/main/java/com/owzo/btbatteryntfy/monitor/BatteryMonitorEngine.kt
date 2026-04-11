package com.owzo.btbatteryntfy.monitor

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.owzo.btbatteryntfy.data.AppPreferences
import com.owzo.btbatteryntfy.data.NotificationReservation
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
    private val readableTimestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
    private val notificationTimestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

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
        val timestamp = formatNotificationTimestamp(sample.observedAtMs)
        val identity = stableDeviceIdentity(sample.deviceName, sample.deviceAddress)

        appPreferences.recordLastSeenBattery(
            deviceIdentity = identity,
            deviceName = sample.deviceName,
            deviceAddress = sample.deviceAddress,
            batteryPercent = sample.batteryPercent,
            observedAtMs = sample.observedAtMs,
        )
        appPreferences.updateLastBatteryEvent(
            "Last battery seen: $deviceLabel ${sample.batteryPercent}% @ $timestamp",
        )
        appPreferences.appendLog("$deviceLabel battery updated: ${sample.batteryPercent}% @ $timestamp.")
    }

    fun processConfirmedDisconnect(
        context: Context,
        deviceIdentity: String,
        fallbackDeviceName: String?,
        fallbackDeviceAddress: String?,
        reason: String,
    ) {
        val appPreferences = AppPreferences(context)
        val settings = appPreferences.loadSettings()
        if (!settings.monitoringEnabled) {
            return
        }

        val state = appPreferences.getTrackedDeviceState(deviceIdentity)
        val deviceLabel = displayName(
            state.deviceName ?: fallbackDeviceName,
            state.deviceAddress ?: fallbackDeviceAddress,
        )

        if (state.isAudioSessionActive) {
            appPreferences.appendLog("$deviceLabel reconnected before disconnect check finished.")
            return
        }

        val batteryPercent = state.lastSeenBattery
        val seenAtMs = state.lastSeenAtMs
        if (batteryPercent !in 0..100 || seenAtMs <= 0L) {
            appPreferences.appendLog("$deviceLabel disconnected, but no battery value was seen during the session.")
            return
        }
        val confirmedBatteryPercent = batteryPercent ?: return

        val endpoint = com.owzo.btbatteryntfy.net.NtfyClient.resolveEndpoint(
            settings.serverUrlOrEndpoint,
            settings.topic,
        )
        if (endpoint == null) {
            appPreferences.updateLastSendResult("Last disconnect summary: invalid ntfy URL or topic")
            appPreferences.appendLog("Disconnect summary for $deviceLabel skipped because ntfy is not configured correctly.")
            return
        }

        val dispatchAttemptAtMs = System.currentTimeMillis()
        when (val reservation = appPreferences.reserveNotificationIfAllowed(deviceIdentity, settings.cooldownMillis, dispatchAttemptAtMs)) {
            is NotificationReservation.CooldownActive -> {
                appPreferences.appendLog(
                    "$deviceLabel disconnect summary is on cooldown until ${formatReadableTimestamp(reservation.nextAllowedAtMs)}.",
                )
            }

            is NotificationReservation.PendingSend -> {
                appPreferences.appendLog("$deviceLabel disconnect summary is already being sent.")
            }

            NotificationReservation.Reserved -> {
                val result = com.owzo.btbatteryntfy.net.NtfyClient.send(
                    endpoint = endpoint,
                    authorizationHeader = settings.authorizationHeader,
                    title = deviceLabel,
                    message = buildDisconnectSummaryMessage(
                        deviceLabel = deviceLabel,
                        batteryPercent = confirmedBatteryPercent,
                        seenAtMs = seenAtMs,
                    ),
                )

                if (result.success) {
                    appPreferences.markNotificationSent(deviceIdentity, confirmedBatteryPercent, dispatchAttemptAtMs)
                    appPreferences.updateLastSendResult(
                        "Last disconnect summary: sent for $deviceLabel at ${confirmedBatteryPercent}%",
                    )
                    appPreferences.appendLog(
                        "Disconnect summary sent for $deviceLabel at ${confirmedBatteryPercent}% ($reason).",
                    )
                } else {
                    appPreferences.clearPendingNotificationReservation(deviceIdentity)
                    appPreferences.updateLastSendResult("Last disconnect summary: ${result.details}")
                    appPreferences.appendLog("Disconnect summary failed for $deviceLabel: ${result.details}.")
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

    fun isLikelyHeadphones(device: BluetoothDevice?): Boolean {
        val bluetoothClass = try {
            device?.bluetoothClass
        } catch (_: SecurityException) {
            null
        }

        return when (bluetoothClass?.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
            -> true

            else -> bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        }
    }

    fun formatTimestamp(timestampMs: Long): String =
        timestampFormatter.format(Instant.ofEpochMilli(timestampMs))

    fun formatReadableTimestamp(timestampMs: Long): String =
        readableTimestampFormatter.format(Instant.ofEpochMilli(timestampMs))

    fun formatNotificationTimestamp(timestampMs: Long): String =
        notificationTimestampFormatter.format(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), ZoneId.systemDefault()),
        )

    private fun buildDisconnectSummaryMessage(
        deviceLabel: String,
        batteryPercent: Int,
        seenAtMs: Long,
    ): String {
        return buildString {
            append(deviceLabel)
            append('\n')
            append("Battery: ")
            append(batteryPercent)
            append("% @ ")
            append(formatNotificationTimestamp(seenAtMs))
        }
    }
}

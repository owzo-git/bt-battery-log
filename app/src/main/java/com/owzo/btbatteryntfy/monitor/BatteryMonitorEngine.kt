package com.owzo.btbatteryntfy.monitor

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.owzo.btbatteryntfy.data.ActivityEventType
import com.owzo.btbatteryntfy.data.AppPreferences
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
    private val disconnectClockFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm")

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
        val previousState = appPreferences.getTrackedDeviceState(identity)
        val previousBattery = previousState.lastSeenBattery

        appPreferences.recordLastSeenBattery(
            deviceIdentity = identity,
            deviceName = sample.deviceName,
            deviceAddress = sample.deviceAddress,
            batteryPercent = sample.batteryPercent,
            observedAtMs = sample.observedAtMs,
        )
        appPreferences.clearDismissedSummaryMarker()

        val connectedText = "$deviceLabel — ${sample.batteryPercent}%"
        appPreferences.setConnectedNotificationState(
            deviceIdentity = identity,
            deviceName = sample.deviceName,
            deviceAddress = sample.deviceAddress,
            batteryPercent = sample.batteryPercent,
            contentText = connectedText,
        )
        BatteryNotificationController.showConnected(
            context = context,
            contentText = connectedText,
            deviceIdentity = identity,
            deviceName = sample.deviceName,
            deviceAddress = sample.deviceAddress,
            batteryPercent = sample.batteryPercent,
        )

        appPreferences.updateLastBatteryEvent(
            "Last battery seen: $deviceLabel ${sample.batteryPercent}% @ $timestamp",
        )

        when {
            previousBattery == null -> {
                appPreferences.appendActivityEvent(
                    deviceLabel = deviceLabel,
                    batteryPercent = sample.batteryPercent,
                    eventType = ActivityEventType.CONNECTED,
                    eventAtMs = sample.observedAtMs,
                )
                appPreferences.appendDebugLog("$deviceLabel battery received: ${sample.batteryPercent}%.")
            }

            previousBattery != sample.batteryPercent -> {
                appPreferences.appendActivityEvent(
                    deviceLabel = deviceLabel,
                    batteryPercent = sample.batteryPercent,
                    eventType = ActivityEventType.UPDATED,
                    eventAtMs = sample.observedAtMs,
                )
                appPreferences.appendDebugLog(
                    "$deviceLabel battery changed: $previousBattery% — ${sample.batteryPercent}%.",
                )
            }
        }
    }

    fun processConfirmedDisconnect(
        context: Context,
        deviceIdentity: String,
        fallbackDeviceName: String?,
        fallbackDeviceAddress: String?,
        reason: String,
    ) {
        val appPreferences = AppPreferences(context)
        appPreferences.clearDisconnectCheckPending(deviceIdentity)

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
            appPreferences.appendDebugLog("$deviceLabel reconnected before disconnect check finished.")
            return
        }

        appPreferences.clearConnectedNotificationState()

        val batteryPercent = state.lastSeenBattery
        val seenAtMs = state.lastSeenAtMs
        if (batteryPercent !in 0..100 || seenAtMs <= 0L) {
            appPreferences.appendDebugLog(
                "$deviceLabel disconnected, but no battery value was seen during the session.",
            )
            BatteryNotificationController.stopConnectedNotificationService(context)
            return
        }

        val disconnectedAtMs = System.currentTimeMillis()
        val disconnectClock = disconnectClockFormatter.format(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(disconnectedAtMs), ZoneId.systemDefault()),
        )
        val summaryText = "$deviceLabel disconnected at $batteryPercent% — $disconnectClock"
        val summarySignature = "$deviceIdentity|$batteryPercent|$seenAtMs"

        if (appPreferences.shouldSkipDisconnectSummary(deviceIdentity, summarySignature)) {
            appPreferences.appendDebugLog("Duplicate disconnect summary suppressed for $deviceLabel.")
            return
        }

        BatteryNotificationController.showDisconnectSummary(
            context = context,
            contentText = summaryText,
            summarySignature = summarySignature,
        )

        appPreferences.markDisconnectSummaryShown(
            deviceIdentity = deviceIdentity,
            summarySignature = summarySignature,
            disconnectedAtMs = disconnectedAtMs,
        )
        appPreferences.updateLastDisconnectSummary("Last disconnect summary: $summaryText")
        appPreferences.appendActivityEvent(
            deviceLabel = deviceLabel,
            batteryPercent = batteryPercent,
            eventType = ActivityEventType.DISCONNECTED,
            eventAtMs = disconnectedAtMs,
        )
        appPreferences.appendDebugLog("Disconnect summary notification shown: $summaryText ($reason).")
    }

    fun processDisconnectSummaryDismissed(context: Context, summarySignature: String) {
        if (summarySignature.isBlank()) {
            return
        }

        val appPreferences = AppPreferences(context)
        appPreferences.markDisconnectSummaryDismissed(summarySignature)
        appPreferences.appendDebugLog("Disconnect summary notification dismissed.")

        val parts = summarySignature.split('|')
        val deviceIdentity = parts.getOrNull(0).orEmpty()
        val batteryPercent = parts.getOrNull(1)?.toIntOrNull()
        if (deviceIdentity.isBlank() || batteryPercent !in 0..100) {
            return
        }

        val state = appPreferences.getTrackedDeviceState(deviceIdentity)
        val deviceLabel = displayName(state.deviceName, state.deviceAddress)
        appPreferences.appendActivityEvent(
            deviceLabel = deviceLabel,
            batteryPercent = batteryPercent,
            eventType = ActivityEventType.NOTIFICATION_DISMISSED,
        )
    }

    fun processConnectedNotificationDismissed(context: Context) {
        val appPreferences = AppPreferences(context)
        val settings = appPreferences.loadSettings()
        if (!settings.monitoringEnabled) {
            return
        }

        val state = appPreferences.getConnectedNotificationState() ?: return
        BatteryNotificationController.showConnected(
            context = context,
            contentText = state.contentText,
            deviceIdentity = state.deviceIdentity,
            deviceName = state.deviceName,
            deviceAddress = state.deviceAddress,
            batteryPercent = state.batteryPercent,
        )
        appPreferences.appendDebugLog("Connected notification was dismissed and restored while headset remained connected.")
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
}

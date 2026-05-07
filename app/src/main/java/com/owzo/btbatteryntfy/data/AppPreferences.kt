package com.owzo.btbatteryntfy.data

import android.content.Context
import android.content.SharedPreferences
import com.owzo.btbatteryntfy.AppConstants
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class AudioProfileKey {
    A2DP,
    HEADSET,
}

data class TrackedDeviceState(
    val deviceName: String?,
    val deviceAddress: String?,
    val lastSeenBattery: Int?,
    val lastSeenAtMs: Long,
    val lastDisconnectAtMs: Long,
    val a2dpConnected: Boolean,
    val headsetConnected: Boolean,
    val knownAudioDevice: Boolean,
) {
    val isAudioSessionActive: Boolean
        get() = a2dpConnected || headsetConnected
}

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): AppSettings = synchronized(LOCK) {
        AppSettings(
            disconnectDelaySeconds = prefs.getInt(
                KEY_DISCONNECT_DELAY_SECONDS,
                AppSettings.DEFAULT_DISCONNECT_DELAY_SECONDS,
            ),
            monitoringEnabled = prefs.getBoolean(KEY_MONITORING_ENABLED, false),
        )
    }

    fun saveSettings(settings: AppSettings) = synchronized(LOCK) {
        prefs.edit()
            .putInt(KEY_DISCONNECT_DELAY_SECONDS, settings.disconnectDelaySeconds)
            .putBoolean(KEY_MONITORING_ENABLED, settings.monitoringEnabled)
            .commit()
    }

    fun getStatusSnapshot(
        defaultBatteryEvent: String,
        defaultDisconnectSummary: String,
        defaultLogText: String,
    ): StatusSnapshot = synchronized(LOCK) {
        StatusSnapshot(
            lastBatteryEvent = prefs.getString(KEY_LAST_BATTERY_EVENT, defaultBatteryEvent).orEmpty(),
            lastDisconnectSummary = prefs.getString(KEY_LAST_DISCONNECT_SUMMARY, defaultDisconnectSummary).orEmpty(),
            logText = prefs.getString(KEY_RECENT_LOGS, defaultLogText).orEmpty(),
        )
    }

    fun updateLastBatteryEvent(summary: String) = synchronized(LOCK) {
        prefs.edit().putString(KEY_LAST_BATTERY_EVENT, summary).commit()
    }

    fun updateLastDisconnectSummary(summary: String) = synchronized(LOCK) {
        prefs.edit().putString(KEY_LAST_DISCONNECT_SUMMARY, summary).commit()
    }

    fun appendLog(message: String) {
        synchronized(LOCK) {
            val sanitized = message.replace('\n', ' ').trim()
            if (sanitized.isBlank()) {
                return@synchronized
            }

            val nowMs = System.currentTimeMillis()
            val lastMessage = prefs.getString(KEY_LAST_LOG_MESSAGE, "").orEmpty()
            val lastAtMs = prefs.getLong(KEY_LAST_LOG_MESSAGE_AT_MS, 0L)
            if (sanitized == lastMessage && nowMs - lastAtMs < LOG_DEDUPE_WINDOW_MS) {
                return@synchronized
            }

            val entry = "${logTimeFormatter.format(Instant.ofEpochMilli(nowMs))}  $sanitized"
            val current = prefs.getString(KEY_RECENT_LOGS, "").orEmpty()
            val lines = buildList {
                add(entry)
                if (current.isNotBlank()) {
                    addAll(current.lines().filter { it.isNotBlank() })
                }
            }.take(AppConstants.MAX_LOG_LINES)

            prefs.edit()
                .putString(KEY_RECENT_LOGS, lines.joinToString(separator = "\n"))
                .putString(KEY_LAST_LOG_MESSAGE, sanitized)
                .putLong(KEY_LAST_LOG_MESSAGE_AT_MS, nowMs)
                .commit()
        }
    }

    fun clearLogs() = synchronized(LOCK) {
        prefs.edit()
            .putString(KEY_RECENT_LOGS, "")
            .remove(KEY_LAST_LOG_MESSAGE)
            .remove(KEY_LAST_LOG_MESSAGE_AT_MS)
            .commit()
    }

    fun recordLastSeenBattery(
        deviceIdentity: String,
        deviceName: String?,
        deviceAddress: String?,
        batteryPercent: Int,
        observedAtMs: Long,
    ) = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        prefs.edit()
            .putString("${base}_device_name", deviceName?.trim().orEmpty())
            .putString("${base}_device_address", deviceAddress?.trim().orEmpty())
            .putInt("${base}_last_seen_battery", batteryPercent)
            .putLong("${base}_last_seen_at_ms", observedAtMs)
            .commit()
    }

    fun markAudioProfileConnection(
        deviceIdentity: String,
        deviceName: String?,
        deviceAddress: String?,
        profileKey: AudioProfileKey,
        connected: Boolean,
    ) = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        val editor = prefs.edit()
            .putString("${base}_device_name", deviceName?.trim().orEmpty())
            .putString("${base}_device_address", deviceAddress?.trim().orEmpty())
            .putBoolean("${base}_known_audio_device", true)
            .putBoolean("${base}_${profileKey.storageKey}", connected)

        if (connected) {
            editor.putBoolean("${base}_disconnect_check_pending", false)
        }

        editor.commit()
    }

    fun markAllAudioProfilesDisconnected(
        deviceIdentity: String,
        deviceName: String?,
        deviceAddress: String?,
    ) = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        prefs.edit()
            .putString("${base}_device_name", deviceName?.trim().orEmpty())
            .putString("${base}_device_address", deviceAddress?.trim().orEmpty())
            .putBoolean("${base}_known_audio_device", true)
            .putBoolean("${base}_${AudioProfileKey.A2DP.storageKey}", false)
            .putBoolean("${base}_${AudioProfileKey.HEADSET.storageKey}", false)
            .commit()
    }

    fun getTrackedDeviceState(deviceIdentity: String): TrackedDeviceState = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        TrackedDeviceState(
            deviceName = prefs.getString("${base}_device_name", "").orEmpty().ifBlank { null },
            deviceAddress = prefs.getString("${base}_device_address", "").orEmpty().ifBlank { null },
            lastSeenBattery = if (prefs.contains("${base}_last_seen_battery")) {
                prefs.getInt("${base}_last_seen_battery", -1).takeIf { it in 0..100 }
            } else {
                null
            },
            lastSeenAtMs = prefs.getLong("${base}_last_seen_at_ms", 0L),
            lastDisconnectAtMs = prefs.getLong("${base}_last_disconnect_at_ms", 0L),
            a2dpConnected = prefs.getBoolean("${base}_${AudioProfileKey.A2DP.storageKey}", false),
            headsetConnected = prefs.getBoolean("${base}_${AudioProfileKey.HEADSET.storageKey}", false),
            knownAudioDevice = prefs.getBoolean("${base}_known_audio_device", false),
        )
    }

    fun markDisconnectCheckPending(deviceIdentity: String, requestedAtMs: Long): Boolean = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        if (prefs.getBoolean("${base}_disconnect_check_pending", false)) {
            return@synchronized false
        }

        prefs.edit()
            .putBoolean("${base}_disconnect_check_pending", true)
            .putLong("${base}_disconnect_check_requested_at_ms", requestedAtMs)
            .commit()
        true
    }

    fun clearDisconnectCheckPending(deviceIdentity: String) = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        prefs.edit()
            .putBoolean("${base}_disconnect_check_pending", false)
            .remove("${base}_disconnect_check_requested_at_ms")
            .commit()
    }

    fun shouldSkipDisconnectSummary(deviceIdentity: String, summarySignature: String): Boolean = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        val lastShownSignature = prefs.getString("${base}_last_disconnect_signature", "").orEmpty()
        val dismissedSignature = prefs.getString(KEY_DISMISSED_DISCONNECT_SIGNATURE, "").orEmpty()

        summarySignature == lastShownSignature || summarySignature == dismissedSignature
    }

    fun markDisconnectSummaryShown(
        deviceIdentity: String,
        summarySignature: String,
        disconnectedAtMs: Long,
    ) = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        prefs.edit()
            .putString("${base}_last_disconnect_signature", summarySignature)
            .putLong("${base}_last_disconnect_at_ms", disconnectedAtMs)
            .commit()
    }

    fun markDisconnectSummaryDismissed(summarySignature: String) = synchronized(LOCK) {
        prefs.edit().putString(KEY_DISMISSED_DISCONNECT_SIGNATURE, summarySignature).commit()
    }

    fun clearDismissedSummaryMarker() = synchronized(LOCK) {
        prefs.edit().remove(KEY_DISMISSED_DISCONNECT_SIGNATURE).commit()
    }

    fun rawPreferences(): SharedPreferences = prefs

    private fun deviceBase(deviceIdentity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deviceIdentity.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(24)
        return "device_$digest"
    }

    companion object {
        private const val KEY_DISCONNECT_DELAY_SECONDS = "settings_disconnect_delay_seconds"
        private const val KEY_MONITORING_ENABLED = "settings_monitoring_enabled"

        private const val KEY_LAST_BATTERY_EVENT = "status_last_battery_event"
        private const val KEY_LAST_DISCONNECT_SUMMARY = "status_last_disconnect_summary"
        private const val KEY_RECENT_LOGS = "status_recent_logs"

        private const val KEY_LAST_LOG_MESSAGE = "status_last_log_message"
        private const val KEY_LAST_LOG_MESSAGE_AT_MS = "status_last_log_message_at_ms"
        private const val LOG_DEDUPE_WINDOW_MS = 8_000L

        private const val KEY_DISMISSED_DISCONNECT_SIGNATURE = "status_dismissed_disconnect_signature"

        private val logTimeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

        private val LOCK = Any()
    }
}

private val AudioProfileKey.storageKey: String
    get() = when (this) {
        AudioProfileKey.A2DP -> "a2dp_connected"
        AudioProfileKey.HEADSET -> "headset_connected"
    }

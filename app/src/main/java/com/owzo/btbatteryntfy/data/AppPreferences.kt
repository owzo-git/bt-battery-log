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

sealed interface NotificationReservation {
    data object Reserved : NotificationReservation
    data class CooldownActive(val nextAllowedAtMs: Long) : NotificationReservation
    data class PendingSend(val leaseExpiresAtMs: Long) : NotificationReservation
}

enum class AudioProfileKey {
    A2DP,
    HEADSET,
}

data class TrackedDeviceState(
    val deviceName: String?,
    val deviceAddress: String?,
    val lastSeenBattery: Int?,
    val lastSeenAtMs: Long,
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
            serverUrlOrEndpoint = prefs.getString(KEY_SERVER_URL, "").orEmpty(),
            topic = prefs.getString(KEY_TOPIC, "").orEmpty(),
            authorizationHeader = prefs.getString(KEY_AUTHORIZATION, "").orEmpty(),
            cooldownHours = prefs.getInt(KEY_COOLDOWN_HOURS, AppSettings.DEFAULT_COOLDOWN_HOURS),
            disconnectDelaySeconds = prefs.getInt(
                KEY_DISCONNECT_DELAY_SECONDS,
                AppSettings.DEFAULT_DISCONNECT_DELAY_SECONDS,
            ),
            monitoringEnabled = prefs.getBoolean(KEY_MONITORING_ENABLED, false),
        )
    }

    fun saveSettings(settings: AppSettings) = synchronized(LOCK) {
        prefs.edit()
            .putString(KEY_SERVER_URL, settings.serverUrlOrEndpoint.trim())
            .putString(KEY_TOPIC, settings.topic.trim())
            .putString(KEY_AUTHORIZATION, settings.authorizationHeader.trim())
            .putInt(KEY_COOLDOWN_HOURS, settings.cooldownHours)
            .putInt(KEY_DISCONNECT_DELAY_SECONDS, settings.disconnectDelaySeconds)
            .putBoolean(KEY_MONITORING_ENABLED, settings.monitoringEnabled)
            .commit()
    }

    fun getStatusSnapshot(
        defaultBatteryEvent: String,
        defaultSendResult: String,
        defaultLogText: String,
    ): StatusSnapshot = synchronized(LOCK) {
        StatusSnapshot(
            lastBatteryEvent = prefs.getString(KEY_LAST_BATTERY_EVENT, defaultBatteryEvent).orEmpty(),
            lastSendResult = prefs.getString(KEY_LAST_SEND_RESULT, defaultSendResult).orEmpty(),
            logText = prefs.getString(KEY_RECENT_LOGS, defaultLogText).orEmpty(),
        )
    }

    fun updateLastBatteryEvent(summary: String) = synchronized(LOCK) {
        prefs.edit().putString(KEY_LAST_BATTERY_EVENT, summary).commit()
    }

    fun updateLastSendResult(summary: String) = synchronized(LOCK) {
        prefs.edit().putString(KEY_LAST_SEND_RESULT, summary).commit()
    }

    fun appendLog(message: String) {
        synchronized(LOCK) {
        val sanitized = message.replace('\n', ' ').trim()
        if (sanitized.isBlank()) {
            return@synchronized
        }

        val entry = "${logTimeFormatter.format(Instant.now())}  $sanitized"
        val current = prefs.getString(KEY_RECENT_LOGS, "").orEmpty()
        val lines = buildList {
            add(entry)
            if (current.isNotBlank()) {
                addAll(current.lines().filter { it.isNotBlank() })
            }
        }.take(AppConstants.MAX_LOG_LINES)

        prefs.edit()
            .putString(KEY_RECENT_LOGS, lines.joinToString(separator = "\n"))
            .commit()
        }
    }

    fun clearLogs() = synchronized(LOCK) {
        prefs.edit().putString(KEY_RECENT_LOGS, "").commit()
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
        prefs.edit()
            .putString("${base}_device_name", deviceName?.trim().orEmpty())
            .putString("${base}_device_address", deviceAddress?.trim().orEmpty())
            .putBoolean("${base}_known_audio_device", true)
            .putBoolean("${base}_${profileKey.storageKey}", connected)
            .commit()
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
            a2dpConnected = prefs.getBoolean("${base}_${AudioProfileKey.A2DP.storageKey}", false),
            headsetConnected = prefs.getBoolean("${base}_${AudioProfileKey.HEADSET.storageKey}", false),
            knownAudioDevice = prefs.getBoolean("${base}_known_audio_device", false),
        )
    }

    fun reserveNotificationIfAllowed(
        deviceIdentity: String,
        cooldownMillis: Long,
        nowMs: Long,
    ): NotificationReservation = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        val lastDispatchMs = prefs.getLong("${base}_last_dispatch_ms", 0L)
        if (lastDispatchMs > 0L && nowMs - lastDispatchMs < cooldownMillis) {
            return NotificationReservation.CooldownActive(lastDispatchMs + cooldownMillis)
        }

        val pendingUntilMs = prefs.getLong("${base}_pending_send_until_ms", 0L)
        if (pendingUntilMs > nowMs) {
            return NotificationReservation.PendingSend(pendingUntilMs)
        }

        prefs.edit()
            .putLong("${base}_pending_send_until_ms", nowMs + NOTIFICATION_PENDING_LEASE_MS)
            .commit()
        NotificationReservation.Reserved
    }

    fun markNotificationSent(
        deviceIdentity: String,
        batteryPercent: Int,
        timestampMs: Long,
    ) = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        prefs.edit()
            .putLong("${base}_last_dispatch_ms", timestampMs)
            .putInt("${base}_last_notified_battery", batteryPercent)
            .putLong("${base}_last_notified_at_ms", timestampMs)
            .remove("${base}_pending_send_until_ms")
            .commit()
    }

    fun clearPendingNotificationReservation(deviceIdentity: String) = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        prefs.edit()
            .remove("${base}_pending_send_until_ms")
            .commit()
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
        private const val KEY_SERVER_URL = "settings_server_url"
        private const val KEY_TOPIC = "settings_topic"
        private const val KEY_AUTHORIZATION = "settings_authorization"
        private const val KEY_COOLDOWN_HOURS = "settings_cooldown_hours"
        private const val KEY_DISCONNECT_DELAY_SECONDS = "settings_disconnect_delay_seconds"
        private const val KEY_MONITORING_ENABLED = "settings_monitoring_enabled"
        private const val KEY_LAST_BATTERY_EVENT = "status_last_battery_event"
        private const val KEY_LAST_SEND_RESULT = "status_last_send_result"
        private const val KEY_RECENT_LOGS = "status_recent_logs"
        private const val NOTIFICATION_PENDING_LEASE_MS = 2 * 60 * 1000L
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

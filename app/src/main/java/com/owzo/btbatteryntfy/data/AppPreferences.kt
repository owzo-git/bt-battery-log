package com.owzo.btbatteryntfy.data

import android.content.Context
import android.content.SharedPreferences
import com.owzo.btbatteryntfy.AppConstants
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

sealed interface NotificationReservation {
    data object Reserved : NotificationReservation
    data class CooldownActive(val nextAllowedAtMs: Long) : NotificationReservation
    data class PendingSend(val leaseExpiresAtMs: Long) : NotificationReservation
}

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): AppSettings = synchronized(LOCK) {
        AppSettings(
            serverUrlOrEndpoint = prefs.getString(KEY_SERVER_URL, "").orEmpty(),
            topic = prefs.getString(KEY_TOPIC, "").orEmpty(),
            authorizationHeader = prefs.getString(KEY_AUTHORIZATION, "").orEmpty(),
            lowThreshold = prefs.getInt(KEY_LOW_THRESHOLD, AppSettings.DEFAULT_LOW_THRESHOLD),
            cooldownHours = prefs.getInt(KEY_COOLDOWN_HOURS, AppSettings.DEFAULT_COOLDOWN_HOURS),
            monitoringEnabled = prefs.getBoolean(KEY_MONITORING_ENABLED, false),
        )
    }

    fun saveSettings(settings: AppSettings) = synchronized(LOCK) {
        prefs.edit()
            .putString(KEY_SERVER_URL, settings.serverUrlOrEndpoint.trim())
            .putString(KEY_TOPIC, settings.topic.trim())
            .putString(KEY_AUTHORIZATION, settings.authorizationHeader.trim())
            .putInt(KEY_LOW_THRESHOLD, settings.lowThreshold)
            .putInt(KEY_COOLDOWN_HOURS, settings.cooldownHours)
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

    fun appendLog(message: String) = synchronized(LOCK) {
        val sanitized = message.replace('\n', ' ').trim()
        val current = prefs.getString(KEY_RECENT_LOGS, "").orEmpty()
        val lines = buildList {
            if (current.isNotBlank()) {
                addAll(current.lines().filter { it.isNotBlank() })
            }
            add(sanitized)
        }.takeLast(AppConstants.MAX_LOG_LINES)

        prefs.edit()
            .putString(KEY_RECENT_LOGS, lines.joinToString(separator = "\n"))
            .commit()
    }

    fun clearLogs() = synchronized(LOCK) {
        prefs.edit().putString(KEY_RECENT_LOGS, "").commit()
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
        private const val KEY_LOW_THRESHOLD = "settings_low_threshold"
        private const val KEY_COOLDOWN_HOURS = "settings_cooldown_hours"
        private const val KEY_MONITORING_ENABLED = "settings_monitoring_enabled"
        private const val KEY_LAST_BATTERY_EVENT = "status_last_battery_event"
        private const val KEY_LAST_SEND_RESULT = "status_last_send_result"
        private const val KEY_RECENT_LOGS = "status_recent_logs"
        private const val NOTIFICATION_PENDING_LEASE_MS = 2 * 60 * 1000L

        private val LOCK = Any()
    }
}

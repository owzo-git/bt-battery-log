package com.owzo.btbatteryntfy.data

import android.content.Context
import android.content.SharedPreferences
import com.owzo.btbatteryntfy.AppConstants
import com.owzo.btbatteryntfy.monitor.shokz.ShokzComponentReading
import com.owzo.btbatteryntfy.monitor.shokz.ShokzComponents
import com.owzo.btbatteryntfy.monitor.shokz.ShokzReadPolicy
import com.owzo.btbatteryntfy.monitor.shokz.ShokzReadTrigger
import com.owzo.btbatteryntfy.monitor.shokz.ShokzSessionState
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
            shokzVendorReadEnabled = prefs.getBoolean(KEY_SHOKZ_VENDOR_READ_ENABLED, false),
        )
    }

    fun saveSettings(settings: AppSettings) = synchronized(LOCK) {
        prefs.edit()
            .putInt(KEY_DISCONNECT_DELAY_SECONDS, settings.disconnectDelaySeconds)
            .putBoolean(KEY_MONITORING_ENABLED, settings.monitoringEnabled)
            .putBoolean(KEY_SHOKZ_VENDOR_READ_ENABLED, settings.shokzVendorReadEnabled)
            .commit()
    }

    fun getStatusSnapshot(
        defaultBatteryEvent: String,
        defaultDisconnectSummary: String,
        defaultActivityFeedText: String,
        defaultDebugLogText: String,
    ): StatusSnapshot = synchronized(LOCK) {
        StatusSnapshot(
            lastBatteryEvent = prefs.getString(KEY_LAST_BATTERY_EVENT, defaultBatteryEvent).orEmpty(),
            lastDisconnectSummary = prefs.getString(KEY_LAST_DISCONNECT_SUMMARY, defaultDisconnectSummary).orEmpty(),
            activityFeedText = prefs.getString(KEY_ACTIVITY_FEED, defaultActivityFeedText).orEmpty(),
            debugLogText = prefs.getString(KEY_DEBUG_LOGS, defaultDebugLogText).orEmpty(),
        )
    }

    fun updateLastBatteryEvent(summary: String) = synchronized(LOCK) {
        prefs.edit().putString(KEY_LAST_BATTERY_EVENT, summary).commit()
    }

    fun updateLastDisconnectSummary(summary: String) = synchronized(LOCK) {
        prefs.edit().putString(KEY_LAST_DISCONNECT_SUMMARY, summary).commit()
    }

    fun appendActivityEvent(
        deviceLabel: String,
        batteryPercent: Int?,
        eventType: ActivityEventType,
        eventAtMs: Long = System.currentTimeMillis(),
    ) {
        if (deviceLabel.isBlank()) {
            return
        }

        if (batteryPercent !in 0..100) {
            return
        }

        synchronized(LOCK) {
            val normalizedDevice = deviceLabel.trim()
            val signature = "${normalizedDevice.lowercase(Locale.US)}|$batteryPercent|${eventType.label}"
            val lastSignature = prefs.getString(KEY_LAST_ACTIVITY_SIGNATURE, "").orEmpty()
            val lastAtMs = prefs.getLong(KEY_LAST_ACTIVITY_AT_MS, 0L)
            if (signature == lastSignature && eventAtMs - lastAtMs < ACTIVITY_DEDUPE_WINDOW_MS) {
                return@synchronized
            }

            val entry = buildString {
                append(activityTimeFormatter.format(Instant.ofEpochMilli(eventAtMs)))
                append(' ')
                append(normalizedDevice)
                append(' ')
                append(batteryPercent)
                append("% (")
                append(eventType.label)
                append(')')
            }

            val current = prefs.getString(KEY_ACTIVITY_FEED, "").orEmpty()
            val lines = buildList {
                add(entry)
                if (current.isNotBlank()) {
                    addAll(current.lines().filter { it.isNotBlank() })
                }
            }.take(AppConstants.MAX_ACTIVITY_LINES)

            prefs.edit()
                .putString(KEY_ACTIVITY_FEED, lines.joinToString(separator = "\n"))
                .putString(KEY_LAST_ACTIVITY_SIGNATURE, signature)
                .putLong(KEY_LAST_ACTIVITY_AT_MS, eventAtMs)
                .commit()
        }
    }

    fun appendDebugLog(message: String) {
        synchronized(LOCK) {
            val sanitized = message.replace('\n', ' ').trim()
            if (sanitized.isBlank()) {
                return@synchronized
            }

            val nowMs = System.currentTimeMillis()
            val lastMessage = prefs.getString(KEY_LAST_DEBUG_LOG_MESSAGE, "").orEmpty()
            val lastAtMs = prefs.getLong(KEY_LAST_DEBUG_LOG_MESSAGE_AT_MS, 0L)
            if (sanitized == lastMessage && nowMs - lastAtMs < DEBUG_DEDUPE_WINDOW_MS) {
                return@synchronized
            }

            val entry = "${debugLogTimeFormatter.format(Instant.ofEpochMilli(nowMs))}  $sanitized"
            val current = prefs.getString(KEY_DEBUG_LOGS, "").orEmpty()
            val lines = buildList {
                add(entry)
                if (current.isNotBlank()) {
                    addAll(current.lines().filter { it.isNotBlank() })
                }
            }.take(AppConstants.MAX_LOG_LINES)

            prefs.edit()
                .putString(KEY_DEBUG_LOGS, lines.joinToString(separator = "\n"))
                .putString(KEY_LAST_DEBUG_LOG_MESSAGE, sanitized)
                .putLong(KEY_LAST_DEBUG_LOG_MESSAGE_AT_MS, nowMs)
                .commit()
        }
    }

    fun appendLog(message: String) {
        appendDebugLog(message)
    }

    fun clearLogs() = synchronized(LOCK) {
        prefs.edit()
            .putString(KEY_ACTIVITY_FEED, "")
            .putString(KEY_DEBUG_LOGS, "")
            .remove(KEY_LAST_ACTIVITY_SIGNATURE)
            .remove(KEY_LAST_ACTIVITY_AT_MS)
            .remove(KEY_LAST_DEBUG_LOG_MESSAGE)
            .remove(KEY_LAST_DEBUG_LOG_MESSAGE_AT_MS)
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

    fun setConnectedNotificationState(
        deviceIdentity: String,
        deviceName: String?,
        deviceAddress: String?,
        batteryPercent: Int,
        contentText: String,
    ) = synchronized(LOCK) {
        prefs.edit()
            .putString(KEY_CONNECTED_DEVICE_IDENTITY, deviceIdentity)
            .putString(KEY_CONNECTED_DEVICE_NAME, deviceName?.trim().orEmpty())
            .putString(KEY_CONNECTED_DEVICE_ADDRESS, deviceAddress?.trim().orEmpty())
            .putInt(KEY_CONNECTED_DEVICE_BATTERY, batteryPercent)
            .putString(KEY_CONNECTED_DEVICE_TEXT, contentText)
            .putBoolean(KEY_CONNECTED_NOTIFICATION_ACTIVE, true)
            .commit()
    }

    fun clearConnectedNotificationState() = synchronized(LOCK) {
        prefs.edit()
            .remove(KEY_CONNECTED_DEVICE_IDENTITY)
            .remove(KEY_CONNECTED_DEVICE_NAME)
            .remove(KEY_CONNECTED_DEVICE_ADDRESS)
            .remove(KEY_CONNECTED_DEVICE_BATTERY)
            .remove(KEY_CONNECTED_DEVICE_TEXT)
            .putBoolean(KEY_CONNECTED_NOTIFICATION_ACTIVE, false)
            .commit()
    }

    fun getConnectedNotificationState(): ConnectedNotificationState? = synchronized(LOCK) {
        if (!prefs.getBoolean(KEY_CONNECTED_NOTIFICATION_ACTIVE, false)) {
            return@synchronized null
        }

        val identity = prefs.getString(KEY_CONNECTED_DEVICE_IDENTITY, "").orEmpty()
        val batteryPercent = prefs.getInt(KEY_CONNECTED_DEVICE_BATTERY, -1)
        val contentText = prefs.getString(KEY_CONNECTED_DEVICE_TEXT, "").orEmpty()
        if (identity.isBlank() || batteryPercent !in 0..100 || contentText.isBlank()) {
            return@synchronized null
        }

        ConnectedNotificationState(
            deviceIdentity = identity,
            deviceName = prefs.getString(KEY_CONNECTED_DEVICE_NAME, "").orEmpty().ifBlank { null },
            deviceAddress = prefs.getString(KEY_CONNECTED_DEVICE_ADDRESS, "").orEmpty().ifBlank { null },
            batteryPercent = batteryPercent,
            contentText = contentText,
        )
    }

    fun markAudioProfileConnection(
        deviceIdentity: String,
        deviceName: String?,
        deviceAddress: String?,
        profileKey: AudioProfileKey,
        connected: Boolean,
    ) = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        val wasActive = prefs.getBoolean("${base}_${AudioProfileKey.A2DP.storageKey}", false) ||
            prefs.getBoolean("${base}_${AudioProfileKey.HEADSET.storageKey}", false)
        val editor = prefs.edit()
            .putString("${base}_device_name", deviceName?.trim().orEmpty())
            .putString("${base}_device_address", deviceAddress?.trim().orEmpty())
            .putBoolean("${base}_known_audio_device", true)
            .putBoolean("${base}_${profileKey.storageKey}", connected)

        if (connected && !wasActive) {
            editor.remove("${base}_last_seen_battery")
            editor.remove("${base}_last_seen_at_ms")
            // New connection: vendor readings from before now count as last-known, and a refused
            // vendor channel in the previous connection no longer blocks reads.
            editor.putLong("${base}_session_started_at_ms", System.currentTimeMillis())
            editor.putBoolean("${base}_shokz_blocked", false)
        }

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
        return disconnectSummarySuppressionReason(deviceIdentity, summarySignature) != null
    }

    fun disconnectSummarySuppressionReason(
        deviceIdentity: String,
        summarySignature: String,
    ): String? = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        val lastShownSignature = prefs.getString("${base}_last_disconnect_signature", "").orEmpty()
        val dismissedSignature = prefs.getString(KEY_DISMISSED_DISCONNECT_SIGNATURE, "").orEmpty()

        return when (summarySignature) {
            lastShownSignature -> "already_shown_for_signature"
            dismissedSignature -> "already_dismissed_for_signature"
            else -> null
        }
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

    fun getShokzComponents(deviceIdentity: String): ShokzComponents = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        fun reading(part: String): ShokzComponentReading? {
            val percent = prefs.getInt("${base}_shokz_$part", -1)
            val atMs = prefs.getLong("${base}_shokz_${part}_at_ms", 0L)
            return if (percent in 0..100 && atMs > 0L) ShokzComponentReading(percent, atMs) else null
        }
        ShokzComponents(left = reading("left"), right = reading("right"), case = reading("case"))
    }

    fun saveShokzComponents(
        deviceIdentity: String,
        deviceName: String?,
        components: ShokzComponents,
    ) = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        val editor = prefs.edit()
            .putString(KEY_LAST_SHOKZ_DEVICE_IDENTITY, deviceIdentity)
            .putString(KEY_LAST_SHOKZ_DEVICE_NAME, deviceName?.trim().orEmpty())
        fun put(part: String, reading: ShokzComponentReading?) {
            if (reading == null) {
                editor.remove("${base}_shokz_$part").remove("${base}_shokz_${part}_at_ms")
            } else {
                editor.putInt("${base}_shokz_$part", reading.percent)
                    .putLong("${base}_shokz_${part}_at_ms", reading.sampledAtMs)
            }
        }
        put("left", components.left)
        put("right", components.right)
        put("case", components.case)
        editor.commit()
    }

    /** Identity and name of the last headset with vendor readings, for the status card. */
    fun getLastShokzDevice(): Pair<String, String?>? = synchronized(LOCK) {
        val identity = prefs.getString(KEY_LAST_SHOKZ_DEVICE_IDENTITY, "").orEmpty()
        if (identity.isBlank()) {
            return@synchronized null
        }
        identity to prefs.getString(KEY_LAST_SHOKZ_DEVICE_NAME, "").orEmpty().ifBlank { null }
    }

    fun getShokzSessionState(deviceIdentity: String): ShokzSessionState = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        ShokzSessionState(
            sessionStartedAtMs = prefs.getLong("${base}_session_started_at_ms", 0L),
            lastAttemptAtMs = prefs.getLong("${base}_shokz_last_attempt_at_ms", 0L),
            lastSuccessAtMs = prefs.getLong("${base}_shokz_last_success_at_ms", 0L),
            blockedInSession = prefs.getBoolean("${base}_shokz_blocked", false),
        )
    }

    /**
     * Checks [ShokzReadPolicy] and, if a read is allowed, records the attempt in the same step so
     * concurrent workers never open the vendor channel twice.
     */
    fun tryClaimShokzRead(deviceIdentity: String, trigger: ShokzReadTrigger, nowMs: Long): Boolean =
        synchronized(LOCK) {
            val base = deviceBase(deviceIdentity)
            if (prefs.getLong("${base}_session_started_at_ms", 0L) <= 0L) {
                // Already connected before we saw a connect event (e.g. monitoring just enabled).
                prefs.edit().putLong("${base}_session_started_at_ms", nowMs).commit()
            }
            if (!ShokzReadPolicy.shouldRead(trigger, getShokzSessionState(deviceIdentity), nowMs)) {
                return@synchronized false
            }
            prefs.edit().putLong("${base}_shokz_last_attempt_at_ms", nowMs).commit()
            true
        }

    fun recordShokzReadResult(
        deviceIdentity: String,
        succeededAtMs: Long?,
        blockSession: Boolean,
    ) = synchronized(LOCK) {
        val base = deviceBase(deviceIdentity)
        val editor = prefs.edit()
        if (succeededAtMs != null) {
            editor.putLong("${base}_shokz_last_success_at_ms", succeededAtMs)
        }
        if (blockSession) {
            editor.putBoolean("${base}_shokz_blocked", true)
        }
        editor.commit()
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
        private const val KEY_SHOKZ_VENDOR_READ_ENABLED = "settings_shokz_vendor_read_enabled"

        private const val KEY_LAST_SHOKZ_DEVICE_IDENTITY = "shokz_last_device_identity"
        private const val KEY_LAST_SHOKZ_DEVICE_NAME = "shokz_last_device_name"

        private const val KEY_LAST_BATTERY_EVENT = "status_last_battery_event"
        private const val KEY_LAST_DISCONNECT_SUMMARY = "status_last_disconnect_summary"
        private const val KEY_ACTIVITY_FEED = "status_activity_feed"
        private const val KEY_DEBUG_LOGS = "status_debug_logs"

        private const val KEY_LAST_ACTIVITY_SIGNATURE = "status_last_activity_signature"
        private const val KEY_LAST_ACTIVITY_AT_MS = "status_last_activity_at_ms"
        private const val KEY_LAST_DEBUG_LOG_MESSAGE = "status_last_debug_log_message"
        private const val KEY_LAST_DEBUG_LOG_MESSAGE_AT_MS = "status_last_debug_log_message_at_ms"

        private const val KEY_DISMISSED_DISCONNECT_SIGNATURE = "status_dismissed_disconnect_signature"

        private const val KEY_CONNECTED_NOTIFICATION_ACTIVE = "connected_notification_active"
        private const val KEY_CONNECTED_DEVICE_IDENTITY = "connected_notification_device_identity"
        private const val KEY_CONNECTED_DEVICE_NAME = "connected_notification_device_name"
        private const val KEY_CONNECTED_DEVICE_ADDRESS = "connected_notification_device_address"
        private const val KEY_CONNECTED_DEVICE_BATTERY = "connected_notification_device_battery"
        private const val KEY_CONNECTED_DEVICE_TEXT = "connected_notification_content_text"

        private const val ACTIVITY_DEDUPE_WINDOW_MS = 15_000L
        private const val DEBUG_DEDUPE_WINDOW_MS = 8_000L

        private val debugLogTimeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
        private val activityTimeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

        private val LOCK = Any()
    }
}

private val AudioProfileKey.storageKey: String
    get() = when (this) {
        AudioProfileKey.A2DP -> "a2dp_connected"
        AudioProfileKey.HEADSET -> "headset_connected"
    }

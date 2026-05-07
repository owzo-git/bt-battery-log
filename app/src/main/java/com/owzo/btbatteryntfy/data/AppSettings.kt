package com.owzo.btbatteryntfy.data

data class AppSettings(
    val disconnectDelaySeconds: Int = DEFAULT_DISCONNECT_DELAY_SECONDS,
    val monitoringEnabled: Boolean = false,
) {
    val disconnectDelayMillis: Long
        get() = disconnectDelaySeconds.coerceAtLeast(5).toLong() * 1000L

    companion object {
        const val DEFAULT_DISCONNECT_DELAY_SECONDS = 15
    }
}

data class StatusSnapshot(
    val lastBatteryEvent: String,
    val lastDisconnectSummary: String,
    val activityFeedText: String,
    val debugLogText: String,
)

enum class ActivityEventType(val label: String) {
    CONNECTED("connected"),
    UPDATED("updated"),
    DISCONNECTED("disconnected"),
    NOTIFICATION_DISMISSED("notification dismissed"),
}

data class ConnectedNotificationState(
    val deviceIdentity: String,
    val deviceName: String?,
    val deviceAddress: String?,
    val batteryPercent: Int,
    val contentText: String,
)

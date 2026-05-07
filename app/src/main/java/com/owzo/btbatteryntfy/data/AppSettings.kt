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
    val logText: String,
)

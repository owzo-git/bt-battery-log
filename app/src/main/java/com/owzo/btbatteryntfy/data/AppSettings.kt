package com.owzo.btbatteryntfy.data

data class AppSettings(
    val serverUrlOrEndpoint: String = "",
    val topic: String = "",
    val authorizationHeader: String = "",
    val cooldownHours: Int = DEFAULT_COOLDOWN_HOURS,
    val disconnectDelaySeconds: Int = DEFAULT_DISCONNECT_DELAY_SECONDS,
    val monitoringEnabled: Boolean = false,
) {
    val cooldownMillis: Long
        get() = cooldownHours.coerceAtLeast(1).toLong() * 60L * 60L * 1000L

    val disconnectDelayMillis: Long
        get() = disconnectDelaySeconds.coerceAtLeast(5).toLong() * 1000L

    companion object {
        const val DEFAULT_COOLDOWN_HOURS = 12
        const val DEFAULT_DISCONNECT_DELAY_SECONDS = 15
    }
}

data class StatusSnapshot(
    val lastBatteryEvent: String,
    val lastSendResult: String,
    val logText: String,
)

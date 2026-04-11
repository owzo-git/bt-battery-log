package com.owzo.btbatteryntfy.data

data class AppSettings(
    val serverUrlOrEndpoint: String = "",
    val topic: String = "",
    val authorizationHeader: String = "",
    val lowThreshold: Int = DEFAULT_LOW_THRESHOLD,
    val cooldownHours: Int = DEFAULT_COOLDOWN_HOURS,
    val monitoringEnabled: Boolean = false,
) {
    val cooldownMillis: Long
        get() = cooldownHours.coerceAtLeast(1).toLong() * 60L * 60L * 1000L

    companion object {
        const val DEFAULT_LOW_THRESHOLD = 40
        const val DEFAULT_COOLDOWN_HOURS = 12
    }
}

data class StatusSnapshot(
    val lastBatteryEvent: String,
    val lastSendResult: String,
    val logText: String,
)

package com.owzo.btbatteryntfy

object AppConstants {
    const val PREFS_NAME = "bt_battery_ntfy"

    const val ACTION_BATTERY_LEVEL_CHANGED =
        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    const val EXTRA_BATTERY_LEVEL =
        "android.bluetooth.device.extra.BATTERY_LEVEL"

    const val ACTION_HF_INDICATORS_VALUE_CHANGED =
        "android.bluetooth.headset.action.HF_INDICATORS_VALUE_CHANGED"
    const val EXTRA_HF_INDICATOR_ID =
        "android.bluetooth.headset.extra.HF_INDICATORS_IND_ID"
    const val EXTRA_HF_INDICATOR_VALUE =
        "android.bluetooth.headset.extra.HF_INDICATORS_IND_VALUE"
    const val HF_BATTERY_INDICATOR_ID = 2

    const val IPHONE_ACCEV_COMMAND = "+IPHONEACCEV"
    const val IPHONE_ACCEV_BATTERY_KEY = 1
    const val XEVENT_COMMAND = "+XEVENT"
    const val XEVENT_BATTERY_KEYWORD = "BATTERY"

    const val WORK_REASON = "work_reason"
    const val WORK_DEVICE_ADDRESS = "work_device_address"
    const val WORK_DEVICE_NAME = "work_device_name"
    const val WORK_BATTERY_LEVEL = "work_battery_level"
    const val WORK_SOURCE = "work_source"
    const val WORK_OBSERVED_AT = "work_observed_at"

    const val MAX_LOG_LINES = 40
}

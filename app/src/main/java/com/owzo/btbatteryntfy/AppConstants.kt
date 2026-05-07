package com.owzo.btbatteryntfy

object AppConstants {
    const val PREFS_NAME = "bt_battery_ntfy"
    const val MAX_ACTIVITY_LINES = 40
    const val NOTIFICATION_CHANNEL_ID = "bt_battery_log_channel"
    const val NOTIFICATION_CHANNEL_NAME = "BT Battery Log"
    const val MAIN_NOTIFICATION_ID = 1001
    const val ACTION_NOTIFICATION_DISMISSED =
        "com.owzo.btbatteryntfy.action.NOTIFICATION_DISMISSED"
    const val ACTION_CONNECTED_NOTIFICATION_DISMISSED =
        "com.owzo.btbatteryntfy.action.CONNECTED_NOTIFICATION_DISMISSED"
    const val ACTION_NOTIFICATION_SERVICE_START_OR_UPDATE =
        "com.owzo.btbatteryntfy.action.NOTIFICATION_SERVICE_START_OR_UPDATE"
    const val ACTION_NOTIFICATION_SERVICE_STOP =
        "com.owzo.btbatteryntfy.action.NOTIFICATION_SERVICE_STOP"
    const val EXTRA_NOTIFICATION_SIGNATURE = "extra_notification_signature"
    const val EXTRA_CONNECTED_NOTIFICATION_TEXT = "extra_connected_notification_text"
    const val EXTRA_DEVICE_IDENTITY = "extra_device_identity"
    const val EXTRA_DEVICE_NAME = "extra_device_name"
    const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
    const val EXTRA_BATTERY_PERCENT = "extra_battery_percent"

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
    const val WORK_DEVICE_IDENTITY = "work_device_identity"
    const val WORK_DEVICE_ADDRESS = "work_device_address"
    const val WORK_DEVICE_NAME = "work_device_name"
    const val WORK_BATTERY_LEVEL = "work_battery_level"
    const val WORK_SOURCE = "work_source"
    const val WORK_OBSERVED_AT = "work_observed_at"

    const val MAX_LOG_LINES = 40
}

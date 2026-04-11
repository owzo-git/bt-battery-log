package com.owzo.btbatteryntfy.monitor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import com.owzo.btbatteryntfy.AppConstants
import com.owzo.btbatteryntfy.data.AppPreferences
import kotlin.math.roundToInt

class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val appPreferences = AppPreferences(appContext)
        val settings = appPreferences.loadSettings()
        val device = extractDevice(intent)
        val deviceLabel = BatteryMonitorEngine.displayName(
            BatteryMonitorEngine.safeDeviceName(device),
            device?.address,
        )

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                if (settings.monitoringEnabled) {
                    appPreferences.appendLog("System event $action received. Scheduling Bluetooth snapshot.")
                    MonitoringWorkScheduler.enqueueSnapshot(appContext, reason = action)
                }
            }

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                appPreferences.appendLog(
                    "System receiver got BluetoothAdapter.ACTION_STATE_CHANGED state=${intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)} monitoring=${settings.monitoringEnabled}.",
                )
                if (!settings.monitoringEnabled) {
                    return
                }

                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    appPreferences.appendLog("Bluetooth adapter turned on. Scheduling Bluetooth snapshot.")
                    MonitoringWorkScheduler.enqueueSnapshot(appContext, reason = "adapter_on")
                }
            }

            BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                appPreferences.appendLog(
                    "System receiver got BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED for $deviceLabel state=${intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, BluetoothAdapter.ERROR)} prev=${intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_CONNECTION_STATE, BluetoothAdapter.ERROR)} monitoring=${settings.monitoringEnabled}.",
                )
                if (!settings.monitoringEnabled) {
                    appPreferences.appendLog(
                        "Ignored BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED for $deviceLabel because monitoring is disabled.",
                    )
                    return
                }

                handleAdapterConnectionStateChange(
                    intent = intent,
                    appContext = appContext,
                    appPreferences = appPreferences,
                )
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                appPreferences.appendLog(
                    "System receiver got BluetoothDevice.ACTION_ACL_CONNECTED for $deviceLabel monitoring=${settings.monitoringEnabled}.",
                )
                if (!settings.monitoringEnabled) {
                    appPreferences.appendLog(
                        "Ignored BluetoothDevice.ACTION_ACL_CONNECTED for $deviceLabel because monitoring is disabled.",
                    )
                    return
                }

                val label = deviceLabel
                appPreferences.appendLog("Bluetooth device connected: $label. Scheduling targeted snapshot.")
                MonitoringWorkScheduler.enqueueSnapshot(
                    appContext,
                    reason = "acl_connected",
                    deviceAddress = device?.address,
                )
            }

            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                appPreferences.appendLog(
                    "System receiver got BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED for $deviceLabel state=${intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)} prev=${intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED)} monitoring=${settings.monitoringEnabled}.",
                )
                if (!settings.monitoringEnabled) {
                    appPreferences.appendLog(
                        "Ignored BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED for $deviceLabel because monitoring is disabled.",
                    )
                    return
                }

                handleProfileConnectionStateChange(
                    intent = intent,
                    appContext = appContext,
                    appPreferences = appPreferences,
                    source = "a2dp",
                )
            }

            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                appPreferences.appendLog(
                    "System receiver got BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED for $deviceLabel state=${intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)} prev=${intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED)} monitoring=${settings.monitoringEnabled}.",
                )
                if (!settings.monitoringEnabled) {
                    appPreferences.appendLog(
                        "Ignored BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED for $deviceLabel because monitoring is disabled.",
                    )
                    return
                }

                handleProfileConnectionStateChange(
                    intent = intent,
                    appContext = appContext,
                    appPreferences = appPreferences,
                    source = "headset",
                )
            }

            AppConstants.ACTION_BATTERY_LEVEL_CHANGED -> {
                val battery = intent.getIntExtra(AppConstants.EXTRA_BATTERY_LEVEL, -1)
                appPreferences.appendLog(
                    "System receiver got BATTERY_LEVEL_CHANGED for $deviceLabel battery=$battery monitoring=${settings.monitoringEnabled}.",
                )
                if (!settings.monitoringEnabled) {
                    appPreferences.appendLog(
                        "Ignored BATTERY_LEVEL_CHANGED for $deviceLabel because monitoring is disabled.",
                    )
                    return
                }

                enqueueSampleIfValid(
                    appContext = appContext,
                    device = device,
                    batteryPercent = battery,
                    source = "battery_broadcast",
                )
            }

            AppConstants.ACTION_HF_INDICATORS_VALUE_CHANGED -> {
                val indicatorId = intent.getIntExtra(AppConstants.EXTRA_HF_INDICATOR_ID, -1)
                val value = intent.getIntExtra(AppConstants.EXTRA_HF_INDICATOR_VALUE, -1)
                appPreferences.appendLog(
                    "System receiver got HF_INDICATORS_VALUE_CHANGED for $deviceLabel indicatorId=$indicatorId value=$value monitoring=${settings.monitoringEnabled}.",
                )
                if (!settings.monitoringEnabled) {
                    appPreferences.appendLog(
                        "Ignored HF_INDICATORS_VALUE_CHANGED for $deviceLabel because monitoring is disabled.",
                    )
                    return
                }

                if (indicatorId == AppConstants.HF_BATTERY_INDICATOR_ID) {
                    enqueueSampleIfValid(
                        appContext = appContext,
                        device = device,
                        batteryPercent = value,
                        source = "hf_indicator",
                    )
                }
            }

            BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                val batteryPercent = parseVendorSpecificBattery(intent)
                appPreferences.appendLog(
                    "System receiver got vendor headset event for $deviceLabel parsedBattery=${batteryPercent ?: "<none>"} monitoring=${settings.monitoringEnabled}.",
                )
                if (!settings.monitoringEnabled) {
                    appPreferences.appendLog(
                        "Ignored vendor headset event for $deviceLabel because monitoring is disabled.",
                    )
                    return
                }

                val validBatteryPercent = batteryPercent ?: return
                enqueueSampleIfValid(
                    appContext = appContext,
                    device = device,
                    batteryPercent = validBatteryPercent,
                    source = "vendor_headset_event",
                )
            }
        }
    }

    private fun enqueueSampleIfValid(
        appContext: Context,
        device: BluetoothDevice?,
        batteryPercent: Int,
        source: String,
    ) {
        if (batteryPercent !in 0..100) {
            AppPreferences(appContext).appendLog(
                "Ignored battery sample because value=$batteryPercent is outside 0..100.",
            )
            return
        }

        MonitoringWorkScheduler.enqueueBatterySample(
            context = appContext,
            sample = BatterySample(
                deviceName = BatteryMonitorEngine.safeDeviceName(device),
                deviceAddress = device?.address,
                batteryPercent = batteryPercent,
                observedAtMs = System.currentTimeMillis(),
                source = source,
            ),
        )
    }

    private fun handleProfileConnectionStateChange(
        intent: Intent,
        appContext: Context,
        appPreferences: AppPreferences,
        source: String,
    ) {
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        val device = extractDevice(intent)
        if (state == BluetoothProfile.STATE_CONNECTED) {
            val label = BatteryMonitorEngine.displayName(
                BatteryMonitorEngine.safeDeviceName(device),
                device?.address,
            )
            appPreferences.appendLog("Bluetooth $source profile connected: $label. Scheduling targeted snapshot.")
            MonitoringWorkScheduler.enqueueSnapshot(
                context = appContext,
                reason = "${source}_connected",
                deviceAddress = device?.address,
            )
        }
    }

    private fun handleAdapterConnectionStateChange(
        intent: Intent,
        appContext: Context,
        appPreferences: AppPreferences,
    ) {
        val state = intent.getIntExtra(
            BluetoothAdapter.EXTRA_CONNECTION_STATE,
            BluetoothAdapter.STATE_DISCONNECTED,
        )
        val device = extractDevice(intent)
        if (state == BluetoothAdapter.STATE_CONNECTED) {
            val label = BatteryMonitorEngine.displayName(
                BatteryMonitorEngine.safeDeviceName(device),
                device?.address,
            )
            appPreferences.appendLog("Bluetooth adapter connected to $label. Scheduling snapshot.")
            MonitoringWorkScheduler.enqueueSnapshot(
                context = appContext,
                reason = "adapter_connected",
                deviceAddress = device?.address,
            )
        }
    }

    private fun extractDevice(intent: Intent): BluetoothDevice? =
        IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    private fun parseVendorSpecificBattery(intent: Intent): Int? {
        val command = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD)
            ?: return null
        val args = intent.getStringArrayExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS)
            ?: return null

        if (command == AppConstants.IPHONE_ACCEV_COMMAND && args.size >= 3) {
            val indicatorCount = args.firstOrNull()?.toIntOrNull() ?: return null
            for (index in 0 until indicatorCount) {
                val keyIndex = 1 + (index * 2)
                val valueIndex = keyIndex + 1
                if (valueIndex >= args.size) {
                    break
                }

                val key = args[keyIndex].toIntOrNull() ?: continue
                val rawValue = args[valueIndex].toIntOrNull() ?: continue
                if (key == AppConstants.IPHONE_ACCEV_BATTERY_KEY) {
                    return when {
                        rawValue in 0..9 -> ((rawValue / 9f) * 100f).roundToInt()
                        rawValue in 0..100 -> rawValue
                        else -> null
                    }
                }
            }
        }

        if (command == AppConstants.XEVENT_COMMAND &&
            args.size >= 2 &&
            args[0].equals(AppConstants.XEVENT_BATTERY_KEYWORD, ignoreCase = true)
        ) {
            val rawValue = args[1].toIntOrNull() ?: return null
            if (rawValue in 0..100) {
                return rawValue
            }
        }

        return null
    }
}

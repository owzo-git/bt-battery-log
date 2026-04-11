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
import com.owzo.btbatteryntfy.data.AudioProfileKey
import kotlin.math.roundToInt

class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val appPreferences = AppPreferences(appContext)
        val settings = appPreferences.loadSettings()
        val device = extractDevice(intent)
        val deviceName = BatteryMonitorEngine.safeDeviceName(device)
        val deviceAddress = device?.address
        val deviceLabel = BatteryMonitorEngine.displayName(deviceName, deviceAddress)
        val identity = BatteryMonitorEngine.stableDeviceIdentity(deviceName, deviceAddress)

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                if (settings.monitoringEnabled) {
                    appPreferences.appendLog("Phone restarted. Checking connected headphones.")
                    MonitoringWorkScheduler.enqueueSnapshot(appContext, reason = action)
                }
            }

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                if (!settings.monitoringEnabled) {
                    return
                }

                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    appPreferences.appendLog("Bluetooth turned on. Checking connected headphones.")
                    MonitoringWorkScheduler.enqueueSnapshot(appContext, reason = "adapter_on")
                }
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (!settings.monitoringEnabled || !shouldTrackDevice(appPreferences, identity, device)) {
                    return
                }

                MonitoringWorkScheduler.cancelDisconnectCheck(appContext, identity)
                appPreferences.appendLog("$deviceLabel connected. Checking battery.")
                MonitoringWorkScheduler.enqueueSnapshot(
                    context = appContext,
                    reason = "acl_connected",
                    deviceAddress = deviceAddress,
                )
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (!settings.monitoringEnabled || !shouldTrackDevice(appPreferences, identity, device)) {
                    return
                }

                appPreferences.markAllAudioProfilesDisconnected(identity, deviceName, deviceAddress)
                scheduleDisconnectCheck(
                    appContext = appContext,
                    appPreferences = appPreferences,
                    settings = settings,
                    deviceIdentity = identity,
                    deviceName = deviceName,
                    deviceAddress = deviceAddress,
                    deviceLabel = deviceLabel,
                    reason = "acl_disconnected",
                )
            }

            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                if (!settings.monitoringEnabled) {
                    return
                }

                handleProfileConnectionStateChange(
                    appContext = appContext,
                    appPreferences = appPreferences,
                    settings = settings,
                    device = device,
                    profileKey = AudioProfileKey.A2DP,
                    source = "a2dp",
                    state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED,
                    ),
                )
            }

            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                if (!settings.monitoringEnabled) {
                    return
                }

                handleProfileConnectionStateChange(
                    appContext = appContext,
                    appPreferences = appPreferences,
                    settings = settings,
                    device = device,
                    profileKey = AudioProfileKey.HEADSET,
                    source = "headset",
                    state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED,
                    ),
                )
            }

            AppConstants.ACTION_BATTERY_LEVEL_CHANGED -> {
                if (!settings.monitoringEnabled) {
                    return
                }

                enqueueSampleIfValid(
                    appContext = appContext,
                    device = device,
                    batteryPercent = intent.getIntExtra(AppConstants.EXTRA_BATTERY_LEVEL, -1),
                    source = "battery_broadcast",
                )
            }

            AppConstants.ACTION_HF_INDICATORS_VALUE_CHANGED -> {
                if (!settings.monitoringEnabled) {
                    return
                }

                val indicatorId = intent.getIntExtra(AppConstants.EXTRA_HF_INDICATOR_ID, -1)
                val value = intent.getIntExtra(AppConstants.EXTRA_HF_INDICATOR_VALUE, -1)
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
                if (!settings.monitoringEnabled) {
                    return
                }

                val batteryPercent = parseVendorSpecificBattery(intent) ?: return
                enqueueSampleIfValid(
                    appContext = appContext,
                    device = device,
                    batteryPercent = batteryPercent,
                    source = "vendor_headset_event",
                )
            }
        }
    }

    private fun handleProfileConnectionStateChange(
        appContext: Context,
        appPreferences: AppPreferences,
        settings: com.owzo.btbatteryntfy.data.AppSettings,
        device: BluetoothDevice?,
        profileKey: AudioProfileKey,
        source: String,
        state: Int,
    ) {
        val deviceName = BatteryMonitorEngine.safeDeviceName(device)
        val deviceAddress = device?.address
        val deviceLabel = BatteryMonitorEngine.displayName(deviceName, deviceAddress)
        val identity = BatteryMonitorEngine.stableDeviceIdentity(deviceName, deviceAddress)

        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                appPreferences.markAudioProfileConnection(
                    deviceIdentity = identity,
                    deviceName = deviceName,
                    deviceAddress = deviceAddress,
                    profileKey = profileKey,
                    connected = true,
                )
                MonitoringWorkScheduler.cancelDisconnectCheck(appContext, identity)
                appPreferences.appendLog("$deviceLabel connected. Checking battery.")
                MonitoringWorkScheduler.enqueueSnapshot(
                    context = appContext,
                    reason = "${source}_connected",
                    deviceAddress = deviceAddress,
                )
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                appPreferences.markAudioProfileConnection(
                    deviceIdentity = identity,
                    deviceName = deviceName,
                    deviceAddress = deviceAddress,
                    profileKey = profileKey,
                    connected = false,
                )

                if (!appPreferences.getTrackedDeviceState(identity).isAudioSessionActive) {
                    scheduleDisconnectCheck(
                        appContext = appContext,
                        appPreferences = appPreferences,
                        settings = settings,
                        deviceIdentity = identity,
                        deviceName = deviceName,
                        deviceAddress = deviceAddress,
                        deviceLabel = deviceLabel,
                        reason = "${source}_disconnected",
                    )
                }
            }
        }
    }

    private fun scheduleDisconnectCheck(
        appContext: Context,
        appPreferences: AppPreferences,
        settings: com.owzo.btbatteryntfy.data.AppSettings,
        deviceIdentity: String,
        deviceName: String?,
        deviceAddress: String?,
        deviceLabel: String,
        reason: String,
    ) {
        appPreferences.appendLog(
            "$deviceLabel disconnected. Waiting ${settings.disconnectDelaySeconds}s before sending battery summary.",
        )
        MonitoringWorkScheduler.enqueueDisconnectCheck(
            context = appContext,
            deviceIdentity = deviceIdentity,
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            reason = reason,
            delayMillis = settings.disconnectDelayMillis,
        )
    }

    private fun shouldTrackDevice(
        appPreferences: AppPreferences,
        deviceIdentity: String,
        device: BluetoothDevice?,
    ): Boolean {
        val knownState = appPreferences.getTrackedDeviceState(deviceIdentity)
        return knownState.knownAudioDevice || BatteryMonitorEngine.isLikelyHeadphones(device)
    }

    private fun enqueueSampleIfValid(
        appContext: Context,
        device: BluetoothDevice?,
        batteryPercent: Int,
        source: String,
    ) {
        if (batteryPercent !in 0..100) {
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

    private fun extractDevice(intent: Intent): BluetoothDevice? =
        IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    companion object {
        fun parseVendorSpecificBattery(intent: Intent): Int? {
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
}

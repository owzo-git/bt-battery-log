package com.owzo.btbatteryntfy.monitor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.owzo.btbatteryntfy.AppConstants
import com.owzo.btbatteryntfy.data.AppPreferences
import java.util.LinkedHashMap

class SnapshotWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val appPreferences = AppPreferences(applicationContext)
        val settings = appPreferences.loadSettings()
        if (!settings.monitoringEnabled) {
            return Result.success()
        }

        if (!BatteryMonitorEngine.hasBluetoothConnectPermission(applicationContext)) {
            appPreferences.appendDebugLog("Bluetooth permission missing. Cannot check device battery.")
            return Result.success()
        }

        val bluetoothManager = applicationContext.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            appPreferences.appendDebugLog("No Bluetooth adapter on this device.")
            return Result.success()
        }

        if (!adapter.isEnabled) {
            appPreferences.appendDebugLog("Bluetooth is off.")
            return Result.success()
        }

        val reason = inputData.getString(AppConstants.WORK_REASON).orEmpty().ifBlank { "snapshot" }
        val requestedAddress = inputData.getString(AppConstants.WORK_DEVICE_ADDRESS).orEmpty()
        val manualRefresh = reason == "manual_refresh"
        val devices = collectCandidateDevices(adapter)
        if (requestedAddress.isBlank() && manualRefresh) {
            appPreferences.appendDebugLog("Manual refresh: checking connected headsets.")
        }

        var processedCount = 0
        var requestedDeviceLabel: String? = null
        for (device in devices.values) {
            val label = BatteryMonitorEngine.displayName(
                BatteryMonitorEngine.safeDeviceName(device),
                device.address,
            )
            val identity = BatteryMonitorEngine.stableDeviceIdentity(
                BatteryMonitorEngine.safeDeviceName(device),
                device.address,
            )
            if (requestedAddress.isNotBlank() && device.address == requestedAddress) {
                requestedDeviceLabel = label
            }
            if (requestedAddress.isBlank()) {
                val trackedState = appPreferences.getTrackedDeviceState(identity)
                if (!trackedState.knownAudioDevice && !BatteryMonitorEngine.isLikelyHeadphones(device)) {
                    continue
                }
            }
            val batteryLevel = BluetoothBatteryProbe.readBatteryLevel(device)
            if (batteryLevel == null) {
                continue
            }

            processedCount += 1
            BatteryMonitorEngine.processSample(
                context = applicationContext,
                sample = BatterySample(
                    deviceName = BatteryMonitorEngine.safeDeviceName(device),
                    deviceAddress = device.address,
                    batteryPercent = batteryLevel,
                    observedAtMs = System.currentTimeMillis(),
                    source = "snapshot:$reason",
                ),
            )
        }

        if (processedCount == 0) {
            if (requestedAddress.isNotBlank()) {
                val label = requestedDeviceLabel ?: requestedAddress
                appPreferences.appendDebugLog("No battery data from $label.")
            } else if (manualRefresh) {
                appPreferences.appendDebugLog("No connected headsets exposed a readable battery level.")
            }
        } else if (requestedAddress.isBlank() && manualRefresh) {
            appPreferences.appendDebugLog(
                "Manual refresh found battery data for $processedCount connected headset(s).",
            )
        }

        return Result.success()
    }

    private fun collectCandidateDevices(adapter: BluetoothAdapter): LinkedHashMap<String, BluetoothDevice> {
        val devices = LinkedHashMap<String, BluetoothDevice>()
        val requestedAddress = inputData.getString(AppConstants.WORK_DEVICE_ADDRESS).orEmpty()
        if (requestedAddress.isNotBlank() && BluetoothAdapter.checkBluetoothAddress(requestedAddress)) {
            devices[requestedAddress] = adapter.getRemoteDevice(requestedAddress)
            return devices
        }

        for (device in adapter.bondedDevices) {
            devices.putIfAbsent(device.address, device)
        }
        return devices
    }
}

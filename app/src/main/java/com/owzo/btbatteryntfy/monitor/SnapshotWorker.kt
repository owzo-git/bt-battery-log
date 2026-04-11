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
            appPreferences.appendLog("Bluetooth permission missing. Cannot check device battery.")
            return Result.success()
        }

        val bluetoothManager = applicationContext.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            appPreferences.appendLog("No Bluetooth adapter on this device.")
            return Result.success()
        }

        if (!adapter.isEnabled) {
            appPreferences.appendLog("Bluetooth is off.")
            return Result.success()
        }

        val reason = inputData.getString(AppConstants.WORK_REASON).orEmpty().ifBlank { "snapshot" }
        val requestedAddress = inputData.getString(AppConstants.WORK_DEVICE_ADDRESS).orEmpty()
        val devices = collectCandidateDevices(adapter)
        if (requestedAddress.isBlank()) {
            appPreferences.appendLog("Checking connected Bluetooth devices.")
        }

        var processedCount = 0
        var requestedDeviceLabel: String? = null
        for (device in devices.values) {
            val label = BatteryMonitorEngine.displayName(
                BatteryMonitorEngine.safeDeviceName(device),
                device.address,
            )
            if (requestedAddress.isNotBlank() && device.address == requestedAddress) {
                requestedDeviceLabel = label
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
                appPreferences.appendLog("No battery data from $label.")
            } else {
                appPreferences.appendLog("No connected device exposed a readable battery level.")
            }
        } else if (requestedAddress.isBlank()) {
            appPreferences.appendLog(
                "Found battery data for $processedCount connected device(s).",
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

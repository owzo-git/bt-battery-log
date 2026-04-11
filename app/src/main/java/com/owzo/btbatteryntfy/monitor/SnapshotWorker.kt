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
            appPreferences.appendLog("Bluetooth snapshot skipped because BLUETOOTH_CONNECT is not granted.")
            return Result.success()
        }

        val bluetoothManager = applicationContext.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            appPreferences.appendLog("Bluetooth snapshot skipped because no Bluetooth adapter is available.")
            return Result.success()
        }

        if (!adapter.isEnabled) {
            appPreferences.appendLog("Bluetooth snapshot skipped because Bluetooth is off.")
            return Result.success()
        }

        val reason = inputData.getString(AppConstants.WORK_REASON).orEmpty().ifBlank { "snapshot" }
        val devices = collectCandidateDevices(adapter)
        var processedCount = 0
        for (device in devices.values) {
            val batteryLevel = BluetoothBatteryProbe.readBatteryLevel(device) ?: continue
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
            appPreferences.appendLog(
                "Bluetooth snapshot completed for $reason with no readable battery levels from connected accessories.",
            )
        } else {
            appPreferences.appendLog(
                "Bluetooth snapshot completed for $reason and processed $processedCount battery sample(s).",
            )
        }

        return Result.success()
    }

    private fun collectCandidateDevices(adapter: BluetoothAdapter): LinkedHashMap<String, BluetoothDevice> {
        val devices = LinkedHashMap<String, BluetoothDevice>()
        val requestedAddress = inputData.getString(AppConstants.WORK_DEVICE_ADDRESS).orEmpty()
        if (requestedAddress.isNotBlank() && BluetoothAdapter.checkBluetoothAddress(requestedAddress)) {
            devices[requestedAddress] = adapter.getRemoteDevice(requestedAddress)
        }

        for (device in adapter.bondedDevices) {
            devices.putIfAbsent(device.address, device)
        }
        return devices
    }
}


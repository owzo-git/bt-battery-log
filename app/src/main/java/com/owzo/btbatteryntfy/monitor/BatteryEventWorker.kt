package com.owzo.btbatteryntfy.monitor

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.owzo.btbatteryntfy.AppConstants
import com.owzo.btbatteryntfy.data.AppPreferences

class BatteryEventWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val appPreferences = AppPreferences(applicationContext)
        val battery = inputData.getInt(AppConstants.WORK_BATTERY_LEVEL, -1)
        if (battery !in 0..100) {
            appPreferences.appendLog("BatteryEventWorker ignored invalid battery sample value=$battery.")
            return Result.success()
        }

        appPreferences.appendLog(
            "BatteryEventWorker processing device=${inputData.getString(AppConstants.WORK_DEVICE_NAME).orEmpty().ifBlank { inputData.getString(AppConstants.WORK_DEVICE_ADDRESS).orEmpty() }} battery=$battery source=${inputData.getString(AppConstants.WORK_SOURCE).orEmpty().ifBlank { "battery_event" }}.",
        )
        BatteryMonitorEngine.processSample(
            context = applicationContext,
            sample = BatterySample(
                deviceName = inputData.getString(AppConstants.WORK_DEVICE_NAME),
                deviceAddress = inputData.getString(AppConstants.WORK_DEVICE_ADDRESS),
                batteryPercent = battery,
                observedAtMs = inputData.getLong(AppConstants.WORK_OBSERVED_AT, System.currentTimeMillis()),
                source = inputData.getString(AppConstants.WORK_SOURCE).orEmpty().ifBlank { "battery_event" },
            ),
        )
        return Result.success()
    }
}

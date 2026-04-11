package com.owzo.btbatteryntfy.monitor

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.owzo.btbatteryntfy.AppConstants

class DisconnectCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val deviceIdentity = inputData.getString(AppConstants.WORK_DEVICE_IDENTITY).orEmpty()
        if (deviceIdentity.isBlank()) {
            return Result.success()
        }

        BatteryMonitorEngine.processConfirmedDisconnect(
            context = applicationContext,
            deviceIdentity = deviceIdentity,
            fallbackDeviceName = inputData.getString(AppConstants.WORK_DEVICE_NAME),
            fallbackDeviceAddress = inputData.getString(AppConstants.WORK_DEVICE_ADDRESS),
            reason = inputData.getString(AppConstants.WORK_REASON).orEmpty().ifBlank { "disconnect" },
        )
        return Result.success()
    }
}

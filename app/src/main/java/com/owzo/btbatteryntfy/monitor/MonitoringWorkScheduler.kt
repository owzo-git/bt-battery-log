package com.owzo.btbatteryntfy.monitor

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.owzo.btbatteryntfy.AppConstants
import java.util.concurrent.TimeUnit

object MonitoringWorkScheduler {
    fun enqueueSnapshot(
        context: Context,
        reason: String,
        deviceAddress: String? = null,
    ) {
        val request = OneTimeWorkRequestBuilder<SnapshotWorker>()
            .setInputData(
                workDataOf(
                    AppConstants.WORK_REASON to reason,
                    AppConstants.WORK_DEVICE_ADDRESS to deviceAddress.orEmpty(),
                ),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        val uniqueName = "snapshot:${deviceAddress.orEmpty().ifBlank { "all" }}"
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueBatterySample(
        context: Context,
        sample: BatterySample,
    ) {
        val request = OneTimeWorkRequestBuilder<BatteryEventWorker>()
            .setInputData(
                workDataOf(
                    AppConstants.WORK_DEVICE_NAME to sample.deviceName.orEmpty(),
                    AppConstants.WORK_DEVICE_ADDRESS to sample.deviceAddress.orEmpty(),
                    AppConstants.WORK_BATTERY_LEVEL to sample.batteryPercent,
                    AppConstants.WORK_SOURCE to sample.source,
                    AppConstants.WORK_OBSERVED_AT to sample.observedAtMs,
                ),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }

    fun enqueueDisconnectCheck(
        context: Context,
        deviceIdentity: String,
        deviceName: String?,
        deviceAddress: String?,
        reason: String,
        delayMillis: Long,
    ) {
        val request = OneTimeWorkRequestBuilder<DisconnectCheckWorker>()
            .setInputData(
                workDataOf(
                    AppConstants.WORK_DEVICE_IDENTITY to deviceIdentity,
                    AppConstants.WORK_DEVICE_NAME to deviceName.orEmpty(),
                    AppConstants.WORK_DEVICE_ADDRESS to deviceAddress.orEmpty(),
                    AppConstants.WORK_REASON to reason,
                ),
            )
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                "disconnect:${deviceIdentity}",
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    fun cancelDisconnectCheck(
        context: Context,
        deviceIdentity: String,
    ) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("disconnect:${deviceIdentity}")
    }
}

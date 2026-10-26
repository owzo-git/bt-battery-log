package com.owzo.btbatteryntfy.monitor.shokz

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.owzo.btbatteryntfy.AppConstants
import com.owzo.btbatteryntfy.data.AppPreferences
import com.owzo.btbatteryntfy.data.AppSettings
import com.owzo.btbatteryntfy.monitor.BatteryMonitorEngine
import java.util.concurrent.TimeUnit

/**
 * Feeds opt-in Shokz vendor readings into the normal monitoring flow. Every automatic read goes
 * through [ShokzReadPolicy]; the socket itself is opened only by [ShokzOpenFitBatteryProvider].
 */
object ShokzRefreshCoordinator {

    fun isEnabled(settings: AppSettings): Boolean =
        settings.monitoringEnabled && settings.shokzVendorReadEnabled

    fun isSupported(deviceName: String?): Boolean = ShokzProtocol.isSupportedDeviceName(deviceName)

    /** Queues an automatic read; skipped right away when the policy would refuse it anyway. */
    fun schedule(
        context: Context,
        deviceName: String?,
        deviceAddress: String?,
        trigger: ShokzReadTrigger,
    ) {
        if (deviceAddress.isNullOrBlank() || !isSupported(deviceName)) {
            return
        }
        val appPreferences = AppPreferences(context)
        if (!isEnabled(appPreferences.loadSettings())) {
            return
        }
        val identity = BatteryMonitorEngine.stableDeviceIdentity(deviceName, deviceAddress)
        val state = appPreferences.getShokzSessionState(identity)
        if (state.sessionStartedAtMs > 0L &&
            !ShokzReadPolicy.shouldRead(trigger, state, System.currentTimeMillis())
        ) {
            return
        }

        val delayMs = if (trigger == ShokzReadTrigger.CONNECTED) ShokzReadPolicy.CONNECT_READ_DELAY_MS else 0L
        val request = OneTimeWorkRequestBuilder<ShokzReadWorker>()
            .setInputData(
                workDataOf(
                    AppConstants.WORK_DEVICE_ADDRESS to deviceAddress,
                    AppConstants.WORK_REASON to trigger.name,
                ),
            )
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        // KEEP: a read already waiting covers this trigger too.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueWorkName(deviceAddress), ExistingWorkPolicy.KEEP, request)
    }

    /** Drops a queued read, e.g. when the headset disconnects before it ran. */
    fun cancel(context: Context, deviceAddress: String?) {
        if (deviceAddress.isNullOrBlank()) {
            return
        }
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueWorkName(deviceAddress))
    }

    /**
     * Reads now if [trigger] is allowed. Automatic triggers also need the setting; manual reads
     * always run. Returns null when the read was skipped.
     */
    suspend fun refresh(
        context: Context,
        device: BluetoothDevice,
        trigger: ShokzReadTrigger,
    ): ShokzReadResult? {
        val appPreferences = AppPreferences(context)
        if (trigger != ShokzReadTrigger.MANUAL && !isEnabled(appPreferences.loadSettings())) {
            return null
        }
        val deviceName = BatteryMonitorEngine.safeDeviceName(device)
        val identity = BatteryMonitorEngine.stableDeviceIdentity(deviceName, device.address)
        if (!appPreferences.tryClaimShokzRead(identity, trigger, System.currentTimeMillis())) {
            return null
        }

        val result = ShokzOpenFitBatteryProvider.read(context, device)
        applyResult(context, deviceName, device.address, trigger, result)
        return result
    }

    private fun applyResult(
        context: Context,
        deviceName: String?,
        deviceAddress: String,
        trigger: ShokzReadTrigger,
        result: ShokzReadResult,
    ) {
        val appPreferences = AppPreferences(context)
        val identity = BatteryMonitorEngine.stableDeviceIdentity(deviceName, deviceAddress)
        // Name only: no addresses or raw frames in logs.
        val label = deviceName ?: "Shokz headset"
        val source = trigger.name.lowercase()
        when (result) {
            is ShokzReadResult.Success -> {
                appPreferences.recordShokzReadResult(identity, result.status.sampledAtMs, blockSession = false)
                appPreferences.appendDebugLog("$label vendor read ok ($source, ${result.elapsedMs} ms).")
                BatteryMonitorEngine.processShokzStatus(context, deviceName, deviceAddress, result.status)
            }

            is ShokzReadResult.Failure -> {
                val block = ShokzReadPolicy.blocksSession(result.failure)
                appPreferences.recordShokzReadResult(identity, succeededAtMs = null, blockSession = block)
                appPreferences.appendDebugLog(
                    buildString {
                        append("$label vendor read failed ($source): ${result.failure.label}.")
                        if (block) append(" Automatic reads paused until the headset reconnects.")
                    },
                )
            }
        }
    }

    private fun uniqueWorkName(deviceAddress: String) = "shokz:$deviceAddress"
}

class ShokzReadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val address = inputData.getString(AppConstants.WORK_DEVICE_ADDRESS).orEmpty()
        val trigger = inputData.getString(AppConstants.WORK_REASON)
            ?.let { name -> ShokzReadTrigger.entries.firstOrNull { it.name == name } }
        if (trigger == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            return Result.success()
        }
        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return Result.success()
        // read() itself refuses disconnected devices, so a stale job never opens a socket.
        ShokzRefreshCoordinator.refresh(applicationContext, adapter.getRemoteDevice(address), trigger)
        return Result.success()
    }
}

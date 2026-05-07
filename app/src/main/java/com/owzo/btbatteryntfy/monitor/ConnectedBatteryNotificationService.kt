package com.owzo.btbatteryntfy.monitor

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.owzo.btbatteryntfy.AppConstants
import com.owzo.btbatteryntfy.data.AppPreferences

class ConnectedBatteryNotificationService : Service() {
    private val appPreferences by lazy { AppPreferences(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()

        when (action) {
            AppConstants.ACTION_NOTIFICATION_SERVICE_START_OR_UPDATE -> {
                val contentText = intent?.getStringExtra(AppConstants.EXTRA_CONNECTED_NOTIFICATION_TEXT).orEmpty()
                val deviceIdentity = intent?.getStringExtra(AppConstants.EXTRA_DEVICE_IDENTITY).orEmpty()
                val batteryPercent = intent?.getIntExtra(AppConstants.EXTRA_BATTERY_PERCENT, -1) ?: -1
                if (contentText.isBlank() || deviceIdentity.isBlank() || batteryPercent !in 0..100) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }

                val notification = BatteryNotificationController.buildConnectedNotification(this, contentText)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceCompat.startForeground(
                            this,
                            AppConstants.MAIN_NOTIFICATION_ID,
                            notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                        )
                    } else {
                        startForeground(AppConstants.MAIN_NOTIFICATION_ID, notification)
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Failed to promote connected notification to foreground", error)
                    appPreferences.appendDebugLog(
                        "Failed to promote connected notification to foreground service. " +
                            "Falling back to standard ongoing notification.",
                    )
                }

                appPreferences.setConnectedNotificationState(
                    deviceIdentity = deviceIdentity,
                    deviceName = intent?.getStringExtra(AppConstants.EXTRA_DEVICE_NAME),
                    deviceAddress = intent?.getStringExtra(AppConstants.EXTRA_DEVICE_ADDRESS),
                    batteryPercent = batteryPercent,
                    contentText = contentText,
                )
            }

            AppConstants.ACTION_NOTIFICATION_SERVICE_STOP -> {
                appPreferences.clearConnectedNotificationState()
                val removeNotification = intent?.getBooleanExtra(
                    AppConstants.EXTRA_REMOVE_NOTIFICATION_ON_STOP,
                    true,
                ) ?: true
                stopForeground(
                    if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH,
                )
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }

        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val TAG = "BtBatteryService"
    }
}

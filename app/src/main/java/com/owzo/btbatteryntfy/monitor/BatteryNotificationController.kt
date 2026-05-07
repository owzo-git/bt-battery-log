package com.owzo.btbatteryntfy.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.owzo.btbatteryntfy.AppConstants
import com.owzo.btbatteryntfy.R

object BatteryNotificationController {
    fun showConnected(
        context: Context,
        contentText: String,
        deviceIdentity: String,
        deviceName: String?,
        deviceAddress: String?,
        batteryPercent: Int,
    ) {
        ensureChannel(context)
        val serviceIntent = Intent(context, ConnectedBatteryNotificationService::class.java)
            .setAction(AppConstants.ACTION_NOTIFICATION_SERVICE_START_OR_UPDATE)
            .putExtra(AppConstants.EXTRA_CONNECTED_NOTIFICATION_TEXT, contentText)
            .putExtra(AppConstants.EXTRA_DEVICE_IDENTITY, deviceIdentity)
            .putExtra(AppConstants.EXTRA_DEVICE_NAME, deviceName.orEmpty())
            .putExtra(AppConstants.EXTRA_DEVICE_ADDRESS, deviceAddress.orEmpty())
            .putExtra(AppConstants.EXTRA_BATTERY_PERCENT, batteryPercent)

        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {
            val fallback = buildConnectedNotification(context, contentText)
            NotificationManagerCompat.from(context)
                .notify(AppConstants.MAIN_NOTIFICATION_ID, fallback)
        }
    }

    fun showDisconnectSummary(
        context: Context,
        contentText: String,
        summarySignature: String,
    ) {
        ensureChannel(context)
        stopConnectedNotificationService(context)

        val deleteIntent = Intent(context, SystemEventReceiver::class.java)
            .setAction(AppConstants.ACTION_NOTIFICATION_DISMISSED)
            .putExtra(AppConstants.EXTRA_NOTIFICATION_SIGNATURE, summarySignature)

        val deletePendingIntent = PendingIntent.getBroadcast(
            context,
            summarySignature.hashCode(),
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bt_battery)
            .setContentTitle("BT Battery Log")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setDeleteIntent(deletePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        NotificationManagerCompat.from(context)
            .notify(AppConstants.MAIN_NOTIFICATION_ID, builder.build())
    }

    fun cancelMainNotification(context: Context) {
        stopConnectedNotificationService(context)
        NotificationManagerCompat.from(context).cancel(AppConstants.MAIN_NOTIFICATION_ID)
    }

    fun stopConnectedNotificationService(context: Context) {
        val stopIntent = Intent(context, ConnectedBatteryNotificationService::class.java)
            .setAction(AppConstants.ACTION_NOTIFICATION_SERVICE_STOP)
        runCatching {
            context.startService(stopIntent)
        }
    }

    fun buildConnectedNotification(context: Context, contentText: String): Notification {
        ensureChannel(context)

        val deleteIntent = Intent(context, SystemEventReceiver::class.java)
            .setAction(AppConstants.ACTION_CONNECTED_NOTIFICATION_DISMISSED)
        val deletePendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bt_battery)
            .setContentTitle("BT Battery Log")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(deletePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val existing = notificationManager.getNotificationChannel(AppConstants.NOTIFICATION_CHANNEL_ID)
        if (existing != null) {
            return
        }

        val channel = NotificationChannel(
            AppConstants.NOTIFICATION_CHANNEL_ID,
            AppConstants.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Bluetooth headset battery status"
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(channel)
    }
}

package com.owzo.btbatteryntfy.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.owzo.btbatteryntfy.AppConstants
import com.owzo.btbatteryntfy.R

object BatteryNotificationController {
    fun showConnected(
        context: Context,
        contentText: String,
    ) {
        ensureChannel(context)

        val builder = NotificationCompat.Builder(context, AppConstants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bt_battery)
            .setContentTitle("BT Battery Log")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        NotificationManagerCompat.from(context)
            .notify(AppConstants.MAIN_NOTIFICATION_ID, builder.build())
    }

    fun showDisconnectSummary(
        context: Context,
        contentText: String,
        summarySignature: String,
    ) {
        ensureChannel(context)

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
        NotificationManagerCompat.from(context).cancel(AppConstants.MAIN_NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
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

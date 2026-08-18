package com.taptype.taptypepro.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.taptype.taptypepro.R
import com.taptype.taptypepro.service.TapTypeAccessibilityService

object AccessibilityToggleNotification {
    private const val CHANNEL_ID = "taptype_pro_toggle"
    private const val NOTIFICATION_ID = 2
    const val ACTION_DISABLE = "com.taptype.taptypepro.ACTION_DISABLE_ACCESSIBILITY"

    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.areNotificationsEnabled() != true) return
        }
        createChannel(context)

        val disableIntent = Intent(context, TapTypeAccessibilityService::class.java).apply {
            action = ACTION_DISABLE
        }
        val disablePending = PendingIntent.getService(
            context,
            0,
            disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.toggle_notification_title))
            .setContentText(context.getString(R.string.toggle_notification_text))
            .setOngoing(true)
            .setContentIntent(disablePending)
            .addAction(0, context.getString(R.string.toggle_notification_disable), disablePending)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun hide(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.toggle_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

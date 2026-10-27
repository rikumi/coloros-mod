package com.rikumi.colorosmod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

internal object NotificationLayoutPreview {
    private const val TAG = "notification_layout_preview"
    private const val CHANNEL = "notification_layout_preview_heads_up"
    private const val ID = 1

    @Synchronized
    fun send(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
        val title = context.getString(R.string.ui_notification_layout_test_title)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_HIGH))
        if (!manager.areNotificationsEnabled()
            || manager.getNotificationChannel(CHANNEL).importance < NotificationManager.IMPORTANCE_HIGH) {
            return false
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.ui_notification_layout_test_text))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.ui_notification_layout_test_expanded)))
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .build()
        // Replace the previous preview so each explicit test can alert again.
        manager.cancel(TAG, ID)
        manager.notify(TAG, ID, notification)
        true
    }.getOrDefault(false)
}

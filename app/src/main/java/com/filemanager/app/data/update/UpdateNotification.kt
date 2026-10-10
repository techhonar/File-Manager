package com.filemanager.app.data.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.filemanager.app.MainActivity

/**
 * "Update available", tapped to get it: the app opens and starts the update
 * as Update App does - download, then Android's installer.
 */
object UpdateNotification {

    /** The intent action the tap opens the app with; see MainActivity. */
    const val ACTION_UPDATE = "com.filemanager.app.action.UPDATE"

    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 7001

    fun show(context: Context, version: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Creating it again is a no-op once it exists.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a new version of the app is out"
            },
        )
        val update = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Update available")
            .setContentText("Version $version is out. Tap to update.")
            .setContentIntent(update)
            .setAutoCancel(true)
            .build()
        // Dropped without a word on Android 13 and up if notifications were
        // refused; the update is still in the menu.
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Taken down: the update has been started, or is installed already. */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

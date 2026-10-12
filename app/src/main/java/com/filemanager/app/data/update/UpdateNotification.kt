package com.filemanager.app.data.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.filemanager.app.MainActivity
import com.filemanager.app.R
import com.filemanager.app.data.AppLogos

/**
 * "Version 0.6.4 is ready", tapped to get it: the app opens and starts the
 * update as Update App does - download, then Android's installer. Pulled
 * down, it lists what the version changes.
 */
object UpdateNotification {

    /** The intent action the tap opens the app with; see MainActivity. */
    const val ACTION_UPDATE = "com.filemanager.app.action.UPDATE"

    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 7001

    /** [changes]: the version's changes by title, from its release notes. */
    fun show(context: Context, version: String, changes: List<String> = emptyList()) {
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
        // The app's own logo - the one chosen under Theme > App logo, as the
        // home screen shows it - where a download arrow said nothing about
        // which app it was.
        val logo = runCatching { AppLogos.current(context) }.getOrElse { AppLogos.all.first() }
        val notice = updateNotice(version, changes)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(logo.statusIcon)
            .setColor(context.getColor(R.color.ic_launcher_background))
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .apply { notice.details?.let { setStyle(Notification.BigTextStyle().bigText(it)) } }
            .setContentIntent(update)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(context, logo.statusIcon), "Update", update).build(),
            )
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

/** What the update notification says; [details] is what it lists pulled down. */
data class UpdateNotice(val title: String, val text: String, val details: String?)

/**
 * "Version 0.6.4 is ready" and how much it changes, and pulled down, the
 * changes by title - at most [MAX_LISTED], then how many more.
 */
fun updateNotice(version: String, changes: List<String>): UpdateNotice {
    val text = when (changes.size) {
        0 -> "Tap to update."
        1 -> "1 change. Tap to update."
        else -> "${changes.size} changes. Tap to update."
    }
    val details = if (changes.isEmpty()) {
        null
    } else {
        val listed = changes.take(MAX_LISTED).map { "• $it" }
        val more = changes.size - MAX_LISTED
        (if (more > 0) listed + "and $more more" else listed).joinToString("\n")
    }
    return UpdateNotice(title = "Version $version is ready", text = text, details = details)
}

/** As many as an expanded notification shows before it cuts the text off. */
private const val MAX_LISTED = 6

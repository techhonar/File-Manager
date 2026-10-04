package com.filemanager.app.data.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import com.filemanager.app.FileManagerApp
import com.filemanager.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps network transfers going, and on show, while the app is out of sight.
 *
 * Android freezes an app that is not on screen soon after it leaves, and a
 * download running in it simply stops; a foreground service is the only way
 * to keep it moving, and it has to show a notification - which is the point:
 * the notification is where the progress is. Started by TransferCenter for
 * each transfer, it redraws as they go and stops itself when none are left.
 */
class TransferService : Service() {

    private val center: TransferCenter
        get() = (application as FileManagerApp).transfers

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    /** The newest start, so stopping cannot undo one that came after it. */
    private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        if (intent?.action == ACTION_CANCEL) {
            center.cancelAll()
            return START_NOT_STICKY
        }

        // In the foreground before anything else, whatever there is to show:
        // Android allows a few seconds after being asked, and ends the app if
        // that passes - even when the transfer that asked is already over.
        createChannel(this)
        val notification = progressNotification(this, center.active.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_ID, notification)
        }

        if (watching == null) {
            val manager = getSystemService(NotificationManager::class.java)
            watching = scope.launch {
                center.active.collect { active ->
                    if (active.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStart)
                    } else {
                        manager?.notify(PROGRESS_ID, progressNotification(this@TransferService, active))
                        // Transfers report on every buffer; Android drops
                        // updates that come faster than it allows. A state
                        // flow keeps only the newest value, so what arrives
                        // after the pause is the latest, not a backlog.
                        delay(REDRAW_INTERVAL_MS)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 limits how long a data-sync service may run in a day. Out of
     * time, this stops being one; the transfers themselves carry on for as
     * long as the app is left running.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "transfers"

        /** The FTP server's notification is 1. */
        private const val PROGRESS_ID = 2

        /** Each finished transfer gets its own, numbered from here by id. */
        private const val FINISHED_BASE_ID = 1_000

        private const val ACTION_CANCEL = "com.filemanager.app.CANCEL_TRANSFERS"
        private const val REDRAW_INTERVAL_MS = 500L
        private const val PROGRESS_MAX = 1000

        /**
         * Start, or remind, the service. Allowed only while the app is in view,
         * which is when a transfer is started; refused otherwise, and the
         * transfer then runs without a notification rather than not at all.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, TransferService::class.java))
            }
        }

        /** Tell the user how [transfer] went, in a notification of its own. */
        fun finished(context: Context, transfer: Transfer) {
            val outcome = transfer.outcome ?: return
            createChannel(context)
            val download = transfer.direction == Direction.DOWNLOAD
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(
                    if (download) {
                        android.R.drawable.stat_sys_download_done
                    } else {
                        android.R.drawable.stat_sys_upload_done
                    },
                )
                .setContentTitle(outcome.summary)
                .setContentText(
                    if (download) "From ${transfer.serverLabel}" else "To ${transfer.serverLabel}",
                )
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)
                ?.notify(FINISHED_BASE_ID + transfer.id, notification)
        }

        private fun progressNotification(context: Context, active: List<Transfer>): Notification {
            val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
            val cancel = PendingIntent.getService(
                context,
                0,
                Intent(context, TransferService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = Notification.Builder(context, CHANNEL_ID)
                // The system's own moving arrows: the status bar shows a
                // transfer under way without the shade being opened.
                .setSmallIcon(
                    if (active.any { it.direction == Direction.DOWNLOAD }) {
                        android.R.drawable.stat_sys_download
                    } else {
                        android.R.drawable.stat_sys_upload
                    },
                )
                .setContentTitle(transfersTitle(active))
                .setContentText(transfersText(active, size))
                .setContentIntent(openApp(context))
                .setOngoing(true)
                // Redrawn for progress; only its first appearance should be noticed.
                .setOnlyAlertOnce(true)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        if (active.size > 1) "Cancel all" else "Cancel",
                        cancel,
                    ).build(),
                )
            val fraction = transfersFraction(active)
            if (fraction != null) {
                builder.setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt(), false)
            } else {
                builder.setProgress(0, 0, true)
            }
            return builder.build()
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            // Low importance: progress to glance at, not something to interrupt
            // for. Creating it again is a no-op once it exists.
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transfers",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Downloads from and uploads to network storage"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}

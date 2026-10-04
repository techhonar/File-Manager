package com.filemanager.app.data.ftpd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.text.format.Formatter
import com.filemanager.app.FileManagerApp
import com.filemanager.app.MainActivity
import com.filemanager.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps the FTP server alive while the app is in the background.
 *
 * A plain object would be killed the moment the user left the app, which is
 * precisely when a server is useful - the phone sits on the desk serving
 * files while they work on the laptop. A foreground service with a visible
 * notification is the only way Android allows that, and the notification
 * doubles as the way to stop it without coming back into the app.
 */
class FtpService : Service() {

    private val controller: FtpServerController
        get() = (application as FileManagerApp).ftpServer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Redraws the notification as transfers come and go; see [watch]. */
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                watching?.cancel()
                controller.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                val config = (application as FileManagerApp).ftpSettings.config.value
                // Foreground first, then start serving. Android gives a few
                // seconds to post the notification and kills the process if it
                // does not, and binding a socket can take longer than that if
                // the port is contested.
                startForeground(config)
                try {
                    controller.start(config)
                } catch (e: Exception) {
                    // Recorded rather than logged: the screen that asked for
                    // this is still open, and it is the only place the reason
                    // can be shown.
                    controller.reportFailure(e.message ?: "Could not start the server")
                    controller.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                watch(config)
            }
        }
        // Not sticky: a restart by the system would bring the server back up
        // without the user asking, which is not something a server should do.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        controller.stop()
        super.onDestroy()
    }

    /**
     * Keep the notification saying what is being sent and received.
     *
     * Computers send to this server without the phone showing anything, so the
     * notification is the only place to see that a file is arriving, or how
     * far it has got. Redrawn at most twice a second: transfers report on
     * every buffer, and Android drops updates that come faster than it allows.
     */
    private fun watch(config: FtpServerConfig) {
        watching?.cancel()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        watching = scope.launch {
            controller.activity.transfers.collect { transfers ->
                manager.notify(NOTIFICATION_ID, notification(config, transfers))
                delay(REDRAW_INTERVAL_MS)
            }
        }
    }

    private fun startForeground(config: FtpServerConfig) {
        createChannel()
        val notification = notification(config, emptyList())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(config: FtpServerConfig, transfers: List<ServerTransfer>): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, FtpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing ${sharedLabel(config)} over FTP")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            // Redrawn for progress; only its first appearance should be noticed.
            .setOnlyAlertOnce(true)

        if (transfers.isEmpty()) {
            val address = NetworkAddress.localIpv4()
            builder
                .setContentText(
                    if (address != null) {
                        "ftp://$address:${config.port}"
                    } else {
                        "Not connected to a network"
                    },
                )
                .setSmallIcon(R.mipmap.ic_launcher)
        } else {
            val size = { bytes: Long -> Formatter.formatShortFileSize(this, bytes) }
            builder
                .setContentText(describeServerTransfers(transfers, size))
                // The system's own moving arrows, so the status bar says a
                // transfer is under way without the shade being opened.
                .setSmallIcon(
                    if (transfers.any { it.receiving }) {
                        android.R.drawable.stat_sys_download
                    } else {
                        android.R.drawable.stat_sys_upload
                    },
                )
            val fraction = serverTransferFraction(transfers)
            if (fraction != null) {
                builder.setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt(), false)
            } else {
                builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    /**
     * What is being shared, in the notification's title.
     *
     * The folder name alone, except at the top of internal storage where that
     * name is "0" - which is the real directory but means nothing to anyone
     * reading a notification.
     */
    private fun sharedLabel(config: FtpServerConfig): String {
        val external = Environment.getExternalStorageDirectory()?.absolutePath
        if (config.rootPath == external) return "internal storage"
        return File(config.rootPath).name.ifEmpty { config.rootPath }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // Low importance: this is a status line, not something to interrupt
        // for. Creating it again is a no-op once it exists.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FTP server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the app is sharing files over FTP"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "ftp_server"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.filemanager.app.STOP_FTP"
        private const val REDRAW_INTERVAL_MS = 500L
        private const val PROGRESS_MAX = 1000

        fun start(context: Context) {
            val intent = Intent(context, FtpService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FtpService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

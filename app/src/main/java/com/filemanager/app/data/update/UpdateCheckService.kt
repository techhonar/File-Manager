package com.filemanager.app.data.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.filemanager.app.FileManagerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Looks for a newer release every five minutes - or as soon after as Android
 * lets an app run in the background - and says so in a notification, once
 * for each new version. See AppUpdater.checkAndAnnounce.
 *
 * A job that books the next one rather than a periodic job: Android repeats
 * those no more often than every fifteen minutes. Only with a network, so a
 * phone that is offline is not woken for nothing. Android still holds jobs
 * back while the phone sleeps (Doze), and no app gets round that without
 * keeping the phone awake.
 */
class UpdateCheckService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            try {
                (application as FileManagerApp).updater.checkAndAnnounce()
            } finally {
                jobFinished(params, false)
                // After finishing: booking this job's own id while it runs
                // would stop it.
                schedule(applicationContext)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        // Stopped part way - the network went, say - so tried again later.
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 4201
        private const val INTERVAL_MS = 5 * 60 * 1000L

        /** Book the next check, five minutes on, for when there is a network. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckService::class.java))
                    .setMinimumLatency(INTERVAL_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    // Kept across a restart of the phone.
                    .setPersisted(true)
                    .build(),
            )
        }

        /**
         * Book a check unless one is waiting already. On every launch, as a
         * force stop clears an app's jobs and only opening it again restarts
         * them.
         */
        fun ensureScheduled(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) == null) schedule(context)
        }
    }
}

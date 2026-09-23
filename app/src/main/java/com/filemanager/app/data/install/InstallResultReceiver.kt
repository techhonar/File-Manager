package com.filemanager.app.data.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.filemanager.app.FileManagerApp

/**
 * Hears back from the installer about a bundle BundleInstaller committed.
 *
 * Not exported: only the installer, through the pending intent handed to it,
 * reaches this.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        // The system's own confirmation, which it leaves to the installing app
        // to show. Every install by an app that is not the system's asks.
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirmation(intent)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }

        val label = installedLabel(context, intent)
            ?: intent.getStringExtra(EXTRA_LABEL)
            ?: "the app"
        installOutcomeMessage(status, label, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            ?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            val bundle = intent.getStringExtra(EXTRA_BUNDLE) ?: return
            (context.applicationContext as? FileManagerApp)?.bundleInstaller?.installed(bundle)
        }
    }

    private fun confirmation(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /** The app's own name once installed, rather than the bundle's file name. */
    private fun installedLabel(context: Context, intent: Intent): String? {
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
    }

    companion object {
        /** What to call the app until it is installed and has a name of its own. */
        const val EXTRA_LABEL = "com.filemanager.app.extra.INSTALL_LABEL"

        /** The bundle the app came from, for its game data. */
        const val EXTRA_BUNDLE = "com.filemanager.app.extra.INSTALL_BUNDLE"
    }
}

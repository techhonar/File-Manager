package com.filemanager.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * All-files access, which is what lets the Rust core walk real paths.
 *
 * This is not a runtime permission -- there is no dialog to request. The user
 * has to toggle it in a system Settings page, so the flow is: check, send them
 * to Settings, then re-check when they come back (see [MainActivity]).
 */
object StoragePermission {

    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    /**
     * Intent for the per-app "Allow access to manage all files" screen.
     *
     * Some OEM builds (and some emulator images) do not implement the
     * app-specific page, so fall back to the global list of apps.
     */
    fun settingsIntent(context: Context): Intent {
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        val resolvable = direct.resolveActivity(context.packageManager) != null
        return if (resolvable) {
            direct
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }
}

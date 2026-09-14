package com.filemanager.app.data

import android.content.Context
import android.provider.MediaStore

/**
 * Which app a file came from.
 *
 * MediaStore records the package that created each indexed file, which is how
 * a gallery can say a photo came from the camera or from a messenger. It only
 * knows about files it has indexed - anything written outside the media
 * collections, or by the user over USB, has no owner to report.
 */
object MediaOwner {

    /**
     * Human-readable name of the app that created [path], or null when
     * MediaStore has no record of it.
     */
    fun ownerAppLabel(context: Context, path: String): String? {
        val packageName = ownerPackage(context, path) ?: return null
        return appLabel(context, packageName) ?: packageName
    }

    private fun ownerPackage(context: Context, path: String): String? = runCatching {
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(path),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** The app's display name, falling back to null if it is not installed. */
    private fun appLabel(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()
}

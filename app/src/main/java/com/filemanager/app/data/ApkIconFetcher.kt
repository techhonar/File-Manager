package com.filemanager.app.data

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.File

/**
 * Loads an installer package's own launcher icon as its thumbnail.
 *
 * An .apk is a zip, so image decoders make nothing of it and the list falls
 * back to a generic glyph - every installer looking identical. The icon is
 * already inside the archive; PackageManager can read it without installing
 * anything.
 */
class ApkIconFetcher(
    private val file: File,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.path, 0) ?: return null
        val appInfo = info.applicationInfo ?: return null

        // Without these the loader looks for resources in an installed app of
        // the same package name - which is usually absent, and when it is not
        // would show the installed app's icon instead of the archive's.
        appInfo.sourceDir = file.path
        appInfo.publicSourceDir = file.path

        val icon = runCatching { appInfo.loadIcon(pm) }.getOrNull() ?: return null
        return DrawableResult(drawable = icon, isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            // Coil maps a String model through Uri to File, so this is the
            // type that actually arrives.
            if (!data.extension.equals("apk", ignoreCase = true)) return null
            return ApkIconFetcher(data, context)
        }
    }
}

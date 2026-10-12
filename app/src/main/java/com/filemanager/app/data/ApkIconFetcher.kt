package com.filemanager.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.filemanager.app.data.install.isBundle
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.File
import java.util.zip.ZipFile

/**
 * Loads an installer package's own launcher icon as its thumbnail.
 *
 * An .apk is a zip, so image decoders make nothing of it and the list falls
 * back to a generic glyph - every installer looking identical. The icon is
 * already inside the archive; PackageManager can read it without installing
 * anything.
 *
 * Reading it means parsing the whole package, which for a large game is a
 * pause per row, so each icon is read once and kept: see ThumbnailCache.
 */
class ApkIconFetcher(
    private val file: File,
    private val context: Context,
    private val cache: ThumbnailCache,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        when (val cached = cache.lookup(file)) {
            is ThumbnailCache.Lookup.Picture -> return cached.bitmap.asResult()
            ThumbnailCache.Lookup.NoPicture -> return null
            ThumbnailCache.Lookup.Unknown -> {}
        }
        // A read that failed is not remembered, only one that found no icon:
        // the first may go differently next time.
        val icon = runCatching { if (isBundle(file.name)) bundleIcon() else packageIcon() }
            .getOrElse { return null }
        runCatching { if (icon != null) cache.put(file, icon) else cache.putNoPicture(file) }
        return icon?.asResult()
    }

    /** Null for a package Android cannot read; throws if the read failed. */
    private fun packageIcon(): Bitmap? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.path, 0) ?: return null
        val appInfo = info.applicationInfo ?: return null

        // Without these the loader looks for resources in an installed app of
        // the same package name - which is usually absent, and when it is not
        // would show the installed app's icon instead of the archive's.
        appInfo.sourceDir = file.path
        appInfo.publicSourceDir = file.path

        return render(appInfo.loadIcon(pm))
    }

    /**
     * A split-APK bundle's icon, which it carries as a picture beside the
     * APKs: an .xapk and an .apkm both keep one at the top as icon.png. Reading
     * it from the base APK instead would mean unpacking that first - tens of
     * megabytes for a thumbnail - so a bundle without one shows none.
     */
    private fun bundleIcon(): Bitmap? =
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("icon.png") ?: return null
            decodeForThumbnail(zip.getInputStream(entry).use { it.readBytes() })
        }

    /** Drawn at thumbnail size - through its mask, for an adaptive icon. */
    private fun render(icon: Drawable): Bitmap {
        val size = ThumbnailCache.SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bitmap))
        return bitmap
    }

    private fun Bitmap.asResult() = DrawableResult(
        drawable = BitmapDrawable(context.resources, this),
        isSampled = true,
        dataSource = DataSource.DISK,
    )

    class Factory(private val context: Context) : Fetcher.Factory<File> {
        private val cache = ThumbnailCache.apkIcons(context)

        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            // Coil maps a String model through Uri to File, so this is the
            // type that actually arrives.
            if (!data.extension.equals("apk", ignoreCase = true) && !isBundle(data.name)) return null
            return ApkIconFetcher(data, context, cache)
        }
    }
}

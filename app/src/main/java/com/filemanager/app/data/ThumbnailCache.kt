package com.filemanager.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Thumbnails got out of a file - a video's frame, an installer's icon, a
 * track's cover - kept on disk, one small picture per file.
 *
 * Coil keeps none of these from one run to the next: it does not put local
 * files in its disk cache, since a copy of a file already on disk saves
 * nothing. For these files that reasoning breaks down. What is expensive is
 * not reading the file but getting the picture out of it - seeking into a
 * video and decoding a frame, reading an installer's whole package, parsing a
 * track's tags - and that was being done again on every launch, and whenever
 * the memory cache let a picture go.
 *
 * Keyed by path, size and modification time, so an edited or replaced file
 * gets a fresh thumbnail rather than a stale one.
 */
class ThumbnailCache internal constructor(
    private val dir: File,
    private val format: Format,
    private val maxBytes: Long,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    /** How a kind's thumbnails are stored: JPEG for pictures, PNG to keep transparency. */
    enum class Format(val extension: String) { JPEG("jpg"), PNG("png") }

    /** What the cache knows of a file. */
    sealed interface Lookup {
        /** Not looked at yet, or changed since. */
        data object Unknown : Lookup

        /** Looked at, and found to have no picture in it. */
        data object NoPicture : Lookup

        class Picture(val bitmap: Bitmap) : Lookup
    }

    fun lookup(source: File): Lookup {
        val cached = entryFor(source)
        if (!cached.isFile) return Lookup.Unknown
        // An empty entry is a file with nothing to show; see putNoPicture.
        if (cached.length() == 0L) return Lookup.NoPicture
        BitmapFactory.decodeFile(cached.path)?.let { return Lookup.Picture(it) }
        // Unreadable: dropped, so the picture is got out of the file again.
        cached.delete()
        return Lookup.Unknown
    }

    /** Keep [bitmap] as [source]'s thumbnail. */
    fun put(source: File, bitmap: Bitmap) {
        val encoding = if (format == Format.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        write(entryFor(source)) { out ->
            check(bitmap.compress(encoding, QUALITY, out)) { "could not encode the thumbnail" }
        }
    }

    /**
     * Remember that [source] has no picture in it, so that it is not searched
     * again each time it is shown - for a track without a cover, that is its
     * tags parsed on every launch for nothing. Only for an answer that will
     * not change while the file does not: not for a read that failed.
     */
    fun putNoPicture(source: File) {
        write(entryFor(source)) {}
    }

    /**
     * Written beside the destination and renamed into place. A truncated JPEG
     * still decodes - with its lower part grey - so writing in place would let
     * a process killed mid-write, or two tiles fetching the same file, leave a
     * broken thumbnail that [lookup] never discards.
     */
    private fun write(destination: File, body: (OutputStream) -> Unit) {
        dir.mkdirs()
        val partial = File.createTempFile(destination.name, ".part", dir)
        try {
            partial.outputStream().use(body)
            if (!partial.renameTo(destination)) partial.delete()
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    private fun entryFor(source: File): File {
        val key = "${source.absolutePath}|${source.length()}|${source.lastModified()}"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$digest.${format.extension}")
    }

    /**
     * Drop thumbnails once there are more of them than is sensible, oldest
     * first, so what survives is what was got most recently. By count as
     * well as by size: an empty entry takes no room but is still a file.
     */
    internal fun trim() {
        val (partials, entries) = (dir.listFiles() ?: return).partition { it.extension == "part" }
        // Left by a write the process did not live to finish.
        partials.forEach { it.delete() }
        var total = entries.sumOf { it.length() }
        var count = entries.size
        if (total <= maxBytes && count <= maxEntries) return

        entries.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes && count <= maxEntries) return
            total -= file.length()
            count--
            file.delete()
        }
    }

    companion object {
        /**
         * The side, in pixels, a thumbnail is made at: big enough for a grid
         * tile on a dense screen, small enough to be cheap.
         */
        const val SIZE = 256

        private const val QUALITY = 80
        private const val MAX_ENTRIES = 5000

        /** The folder is the one video thumbnails have always been kept in. */
        fun videos(context: Context) =
            ThumbnailCache(File(context.cacheDir, "video-thumbs"), Format.JPEG, 32L * 1024 * 1024)

        /** PNG, as an icon's corners are transparent. */
        fun apkIcons(context: Context) =
            ThumbnailCache(File(context.cacheDir, "apk-icons"), Format.PNG, 16L * 1024 * 1024)

        fun audioArt(context: Context) =
            ThumbnailCache(File(context.cacheDir, "audio-art"), Format.JPEG, 16L * 1024 * 1024)

        /**
         * Keep each kind within its limits. Called at startup rather than on
         * every write: listing a folder is not worth paying per thumbnail.
         */
        fun trimAll(context: Context) {
            listOf(videos(context), apkIcons(context), audioArt(context)).forEach { cache ->
                runCatching { cache.trim() }
            }
        }
    }
}

/**
 * The bitmap in [bytes], decoded no bigger than a thumbnail of [target]
 * pixels needs. A cover embedded in a track is often 3000 pixels across, which
 * decoded whole is a 36 MB bitmap for a 46 dp square.
 */
internal fun decodeForThumbnail(bytes: ByteArray, target: Int = ThumbnailCache.SIZE): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, target)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/**
 * How much to shrink a picture while decoding it: the largest power of two -
 * the steps the decoder can take - that leaves both sides at least [target].
 */
internal fun thumbnailSampleSize(width: Int, height: Int, target: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
    return sample
}

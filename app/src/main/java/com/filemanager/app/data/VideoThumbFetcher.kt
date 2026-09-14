package com.filemanager.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.File
import java.security.MessageDigest

/**
 * Video thumbnails, decoded once and kept on disk.
 *
 * Coil's own video decoder re-reads the file every time a row scrolls back
 * into view, because Coil does not put local files in its disk cache - there
 * is no point caching a copy of a file that is already on disk. For a video
 * that reasoning breaks down: what is expensive is not reading the file, it is
 * seeking into it and decoding a frame, and that work was being repeated on
 * every pass. On a phone with a slow decoder it makes scrolling crawl.
 *
 * A small JPEG per video, keyed by path, size and modification time, so an
 * edited or replaced file gets a fresh thumbnail rather than a stale one.
 */
class VideoThumbFetcher(
    private val file: File,
    private val context: Context,
    private val requestedSize: Int,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val cached = cacheFile(context, file)

        // Second and later views cost a small JPEG decode instead of a seek.
        if (cached.isFile) {
            BitmapFactory.decodeFile(cached.path)?.let { bitmap ->
                return DrawableResult(
                    drawable = BitmapDrawable(context.resources, bitmap),
                    isSampled = true,
                    dataSource = DataSource.DISK,
                )
            }
            // Unreadable: drop it and fall through to decoding again.
            cached.delete()
        }

        val frame = extractFrame() ?: return null
        runCatching { writeCache(cached, frame) }

        return DrawableResult(
            drawable = BitmapDrawable(context.resources, frame),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    private fun extractFrame(): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            // Scaled during extraction rather than after: decoding a full
            // 4K frame only to shrink it is the expensive part.
            retriever.getScaledFrameAtTime(
                FRAME_TIME_US,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                requestedSize,
                requestedSize,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun writeCache(destination: File, bitmap: Bitmap) {
        destination.parentFile?.mkdirs()
        destination.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.extension.lowercase() !in VIDEO_EXTENSIONS) return null
            return VideoThumbFetcher(data, context, THUMB_SIZE)
        }

        private companion object {
            val VIDEO_EXTENSIONS = setOf(
                "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "mpg", "mpeg", "ts",
            )
        }
    }

    companion object {
        /** Big enough for a grid tile on a dense screen, small enough to be cheap. */
        private const val THUMB_SIZE = 256

        /** One second in: frame zero is often black or a fade-in. */
        private const val FRAME_TIME_US = 1_000_000L

        private const val JPEG_QUALITY = 80

        /**
         * Keyed by path, size and modification time, so replacing a file with
         * a different one of the same name does not keep the old thumbnail.
         */
        private fun cacheFile(context: Context, source: File): File {
            val key = "${source.absolutePath}|${source.length()}|${source.lastModified()}"
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return File(File(context.cacheDir, "video-thumbs"), "$digest.jpg")
        }

        /**
         * Drop cached thumbnails once they pass a sensible size.
         *
         * Called at startup rather than on every write: the cost of listing
         * the directory is not worth paying per thumbnail.
         */
        fun trimCache(context: Context, maxBytes: Long = 32L * 1024 * 1024) {
            val dir = File(context.cacheDir, "video-thumbs")
            val files = dir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return

            // Oldest first, so what survives is what was viewed most recently.
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= maxBytes) return
                total -= file.length()
                file.delete()
            }
        }
    }
}

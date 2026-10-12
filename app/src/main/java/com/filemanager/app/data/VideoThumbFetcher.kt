package com.filemanager.app.data

import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore
import android.util.Size
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.File

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
 * Kept as a small JPEG per video: see ThumbnailCache.
 */
class VideoThumbFetcher(
    private val file: File,
    private val context: Context,
    private val cache: ThumbnailCache,
) : Fetcher {

    private val requestedSize = ThumbnailCache.SIZE

    override suspend fun fetch(): FetchResult? {
        // Second and later views cost a small JPEG decode instead of a seek.
        // A video that gave no frame is not remembered as having none: the
        // decoder fails for reasons of its own, such as too many in use.
        (cache.lookup(file) as? ThumbnailCache.Lookup.Picture)?.let { return it.bitmap.asResult() }

        // The system keeps its own thumbnails for indexed media, already
        // generated and already sized. Asking for one is far cheaper than
        // seeking into a large file ourselves, and the difference grows with
        // the video - which is why big files stayed slow while small ones did
        // not. Only when there is no indexed thumbnail do we decode a frame.
        val frame = systemThumbnail() ?: extractFrame() ?: return null
        runCatching { cache.put(file, frame) }
        return frame.asResult()
    }

    private fun Bitmap.asResult() = DrawableResult(
        drawable = BitmapDrawable(context.resources, this),
        isSampled = true,
        dataSource = DataSource.DISK,
    )

    /**
     * The thumbnail MediaStore has already produced for this file, if any.
     *
     * Returns null for anything the media scanner has not indexed - a file
     * just copied in over USB, say - and for anything it declines to thumbnail.
     */
    private fun systemThumbnail(): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val uri = resolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection,
            "${MediaStore.Video.Media.DATA} = ?",
            arrayOf(file.path),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ContentUris.withAppendedId(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                cursor.getLong(0),
            )
        } ?: return@runCatching null

        resolver.loadThumbnail(uri, Size(requestedSize, requestedSize), null)
    }.getOrNull()

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

    class Factory(private val context: Context) : Fetcher.Factory<File> {
        private val cache = ThumbnailCache.videos(context)

        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.extension.lowercase() !in VIDEO_EXTENSIONS) return null
            return VideoThumbFetcher(data, context, cache)
        }

        private companion object {
            val VIDEO_EXTENSIONS = setOf(
                "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "mpg", "mpeg", "ts",
            )
        }
    }

    companion object {
        /** One second in: frame zero is often black or a fade-in. */
        private const val FRAME_TIME_US = 1_000_000L
    }
}

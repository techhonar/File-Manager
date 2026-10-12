package com.filemanager.app.data

import android.content.Context
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
 * Loads a track's embedded cover art as its thumbnail.
 *
 * An audio file is not an image, so decoders make nothing of it and every
 * track falls back to the same note glyph. Most music carries its artwork
 * inside the file, and MediaMetadataRetriever can pull it out without an
 * index or a media scan.
 *
 * Kept once got, and so is finding that a track has none: either answer
 * otherwise cost the track's tags parsed again on every launch. See
 * ThumbnailCache.
 */
class AudioArtFetcher(
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
        val retriever = MediaMetadataRetriever()
        val art = try {
            retriever.setDataSource(file.path)
            // Shrunk as it is decoded: a cover is often thousands of pixels
            // across, for a square a few dozen wide.
            retriever.embeddedPicture?.let { decodeForThumbnail(it) }
        } catch (_: Exception) {
            // A malformed or unreadable file is not worth surfacing: the row
            // simply falls back to its category glyph. Not remembered, as a
            // read that failed may not fail next time.
            return null
        } finally {
            // Holds a native handle, so it has to be released whatever happened.
            runCatching { retriever.release() }
        }
        runCatching { if (art != null) cache.put(file, art) else cache.putNoPicture(file) }
        return art?.asResult()
    }

    private fun Bitmap.asResult() = DrawableResult(
        drawable = BitmapDrawable(context.resources, this),
        isSampled = true,
        dataSource = DataSource.DISK,
    )

    class Factory(private val context: Context) : Fetcher.Factory<File> {
        private val cache = ThumbnailCache.audioArt(context)

        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.extension.lowercase() !in AUDIO_EXTENSIONS) return null
            return AudioArtFetcher(data, context, cache)
        }

        private companion object {
            /** Formats that carry embedded artwork often enough to be worth trying. */
            val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav")
        }
    }
}

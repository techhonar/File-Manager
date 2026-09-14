package com.filemanager.app.data

import android.content.Context
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

/**
 * Loads a track's embedded cover art as its thumbnail.
 *
 * An audio file is not an image, so decoders make nothing of it and every
 * track falls back to the same note glyph. Most music carries its artwork
 * inside the file, and MediaMetadataRetriever can pull it out without an
 * index or a media scan.
 */
class AudioArtFetcher(
    private val file: File,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val bytes = retriever.embeddedPicture ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            DrawableResult(
                drawable = BitmapDrawable(context.resources, bitmap),
                isSampled = false,
                dataSource = DataSource.DISK,
            )
        } catch (_: Exception) {
            // A malformed or unreadable file is not worth surfacing: the row
            // simply falls back to its category glyph.
            null
        } finally {
            // Holds a native handle, so it has to be released whatever happened.
            runCatching { retriever.release() }
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.extension.lowercase() !in AUDIO_EXTENSIONS) return null
            return AudioArtFetcher(data, context)
        }

        private companion object {
            /** Formats that carry embedded artwork often enough to be worth trying. */
            val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav")
        }
    }
}

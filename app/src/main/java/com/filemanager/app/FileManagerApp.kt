package com.filemanager.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.filemanager.app.data.ApkIconFetcher
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Holds the single [FileRepository] for the process.
 *
 * A dependency-injection framework would be the usual answer here; for an app
 * this size one Application-scoped object is less machinery for the same
 * result. Swap in Hilt if the graph ever grows.
 */
class FileManagerApp : Application(), ImageLoaderFactory {

    val repository: FileRepository by lazy { FileRepository(this) }

    /** Shared by every screen: see FileClipboard for why it cannot live in a
     *  per-folder ViewModel. */
    val clipboard: FileClipboard by lazy { FileClipboard() }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The app-wide image loader, with the decoders the file list needs.
     *
     * Coil out of the box handles still images only, so a video row fell back
     * to a generic glyph and every installer looked the same. VideoFrameDecoder
     * pulls a frame out of a video, and ApkIconFetcher reads an installer's own
     * icon straight from the archive.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
                add(ApkIconFetcher.Factory(this@FileManagerApp))
            }
            // Thumbnails are small and there are a great many of them, so a
            // slice of the heap goes further here than Coil's default.
            .crossfade(true)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Clear out trash older than the retention window. Fire-and-forget:
        // nothing in the UI waits on it, and a failure is not worth surfacing.
        appScope.launch {
            runCatching { repository.purgeExpiredTrash() }
        }
    }
}

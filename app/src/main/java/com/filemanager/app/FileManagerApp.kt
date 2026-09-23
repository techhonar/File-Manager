package com.filemanager.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.filemanager.app.data.VideoThumbFetcher
import coil.decode.VideoFrameDecoder
import com.filemanager.app.data.ApkIconFetcher
import com.filemanager.app.data.AudioArtFetcher
import com.filemanager.app.data.AppSettings
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.PathPrefs
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.ftpd.FtpServerController
import com.filemanager.app.data.ftpd.FtpServerSettings
import com.filemanager.app.data.install.BundleInstaller
import com.filemanager.app.data.remote.RemoteConnections
import com.filemanager.app.data.remote.RemoteServers
import uniffi.filemanager_core.SearchSession
import kotlinx.coroutines.CoroutineScope
import java.io.File
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

    val settings: AppSettings by lazy { AppSettings(this) }

    /** Favourites and pinned paths, shared by every screen that shows files. */
    val paths: PathPrefs by lazy { PathPrefs(this) }

    /**
     * The one search session for the process.
     *
     * Held here rather than made per screen. A session keeps the results it
     * found so that narrowing costs no disk, which is several megabytes on a
     * full device, and one was being created every time a category was
     * opened. Releasing them depended on the view model being cleared and on
     * a garbage collector getting round to the Rust object behind it, so
     * opening and closing categories left memory behind and the app grew
     * slower the longer it was used.
     *
     * There is only ever one search screen, and starting a search replaces
     * whatever the session held, so one is all that is needed.
     *
     * What each category last showed is kept on disk too, in the cache
     * folder, so that it survives Android ending the process - which it does
     * to any app left in the background for long, and after which every
     * category used to open onto a spinner again.
     */
    val searchSession: SearchSession by lazy {
        SearchSession.withCacheDir(File(cacheDir, "category-cache").path)
    }

    /** Saved network locations, and the live connections to them. */
    val remoteServers: RemoteServers by lazy { RemoteServers(this) }

    /**
     * Process-wide, because a connection is worth reusing across screens and
     * costs a handshake to open. Closed when the process goes, which is the
     * only lifecycle a socket pool can sensibly follow here.
     */
    val remoteConnections: RemoteConnections by lazy { RemoteConnections() }

    /**
     * The built-in FTP server and its settings.
     *
     * Held here rather than inside the service so the settings screen can read
     * the running state without binding to it, and so stopping the service
     * cannot leave a server running that nothing has a handle on.
     */
    val ftpServer: FtpServerController by lazy { FtpServerController() }

    val ftpSettings: FtpServerSettings by lazy { FtpServerSettings(this) }

    /** Process-wide: an install outlives the screen it was started from. */
    val bundleInstaller: BundleInstaller by lazy { BundleInstaller(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The app-wide image loader, with the decoders the file list needs.
     *
     * Coil out of the box handles still images only, so videos, installers and
     * tracks all fell back to the same generic glyphs.
     *
     * Order matters: VideoThumbFetcher comes first and serves the formats it
     * knows from a disk cache, and VideoFrameDecoder stays behind it as a
     * fallback for anything it does not. ApkIconFetcher reads an installer's
     * icon out of the archive, AudioArtFetcher a track's embedded cover.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                // Before the stock decoder, so videos come from the disk
                // cache instead of being re-decoded on every scroll.
                add(VideoThumbFetcher.Factory(this@FileManagerApp))
                add(VideoFrameDecoder.Factory())
                add(ApkIconFetcher.Factory(this@FileManagerApp))
                add(AudioArtFetcher.Factory(this@FileManagerApp))
            }
            // Thumbnails are small and there are a great many of them, so a
            // slice of the heap goes further here than Coil's default.
            .crossfade(true)
            // Thumbnails are small and numerous, and the default is a fraction
            // of a fraction of the heap - too little for a folder of photos.
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()

        // Clear out trash older than the retention window. Fire-and-forget:
        // nothing in the UI waits on it, and a failure is not worth surfacing.
        appScope.launch {
            runCatching { repository.purgeExpiredTrash() }
            // Once per launch is enough; doing it per thumbnail would cost a
            // directory listing for every row.
            runCatching { VideoThumbFetcher.trimCache(this@FileManagerApp) }
        }
    }
}

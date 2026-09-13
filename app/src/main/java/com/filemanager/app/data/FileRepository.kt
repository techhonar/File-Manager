package com.filemanager.app.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.filemanager_core.ArchiveEntry
import uniffi.filemanager_core.CancelToken
import uniffi.filemanager_core.DuplicateGroup
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.FilesystemStats
import uniffi.filemanager_core.ProgressListener
import uniffi.filemanager_core.SearchFilter
import uniffi.filemanager_core.SearchSink
import uniffi.filemanager_core.SortOptions
import uniffi.filemanager_core.StorageAnalysis
import uniffi.filemanager_core.StorageSummary
import uniffi.filemanager_core.TrashItem
import uniffi.filemanager_core.TreeStats
import uniffi.filemanager_core.analyzeStorage
import uniffi.filemanager_core.archiveCreate
import uniffi.filemanager_core.archiveExtract
import uniffi.filemanager_core.archiveList
import uniffi.filemanager_core.copyPaths
import uniffi.filemanager_core.deletePaths
import uniffi.filemanager_core.dirSize
import uniffi.filemanager_core.filesInCategory
import uniffi.filemanager_core.filesystemStats
import uniffi.filemanager_core.findDuplicates
import uniffi.filemanager_core.formatSize
import uniffi.filemanager_core.largestFiles
import uniffi.filemanager_core.listDir
import uniffi.filemanager_core.recentFiles
import uniffi.filemanager_core.storageSummary
import uniffi.filemanager_core.trashEmpty
import uniffi.filemanager_core.trashList
import uniffi.filemanager_core.trashMoveMany
import uniffi.filemanager_core.trashPurgeExpired
import uniffi.filemanager_core.trashRestore
import uniffi.filemanager_core.trashSize
import uniffi.filemanager_core.treeStats
import java.io.File

/**
 * The single place Kotlin talks to the Rust core.
 *
 * Every call here is `suspend` on [io], because the Rust functions block the
 * calling thread -- a recursive scan on the main thread would freeze the UI
 * exactly as a Kotlin one would. Keeping that rule in one file means no
 * screen has to remember it.
 */
class FileRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext

    /**
     * Where trashed files live: a hidden folder at the root of primary
     * storage.
     *
     * It used to sit in the app's own external files directory, which is
     * tidier - removed on uninstall, no permission needed - but Android serves
     * `Android/data` through a separate mount. Renaming a file out of DCIM
     * into it therefore fails with EXDEV, so every delete fell back to copying
     * the whole file. Here a rename works, so trashing is instant whatever the
     * file's size.
     *
     * The cost is that the folder outlives an uninstall. That is the same
     * trade the platform's own file managers make, and the Trash screen can
     * empty it.
     */
    private val trashDir: String = resolveTrashDir(appContext)

    // --- Browsing -----------------------------------------------------------

    suspend fun list(path: String, showHidden: Boolean, sort: SortOptions): List<FileEntry> =
        withContext(io) { listDir(path, showHidden, sort) }

    suspend fun directorySize(path: String, cancel: CancelToken? = null): ULong =
        withContext(io) { dirSize(path, cancel) }

    /**
     * Capacity and free space of the filesystem holding [path].
     *
     * One statvfs call, unlike [summary] which walks the whole tree - cheap
     * enough to run for every mounted volume when the home screen opens.
     */
    suspend fun volumeStats(path: String): FilesystemStats =
        withContext(io) { filesystemStats(path) }

    suspend fun stats(paths: List<String>, cancel: CancelToken? = null): TreeStats =
        withContext(io) { treeStats(paths, cancel) }

    // --- File operations ----------------------------------------------------

    suspend fun copy(
        sources: List<String>,
        destination: String,
        overwrite: Boolean = false,
        progress: ProgressListener? = null,
        cancel: CancelToken? = null,
    ): ULong = withContext(io) { copyPaths(sources, destination, overwrite, progress, cancel) }

    /**
     * Move = copy then delete the originals.
     *
     * A plain rename is attempted first: within one volume it is instant, and
     * only a cross-volume move (internal -> SD card) needs the full copy.
     */
    suspend fun move(
        sources: List<String>,
        destination: String,
        overwrite: Boolean = false,
        progress: ProgressListener? = null,
        cancel: CancelToken? = null,
    ): ULong = withContext(io) {
        val remaining = sources.filterNot { source ->
            val from = File(source)
            val to = File(destination, from.name)
            if (to.exists() && !overwrite) false else from.renameTo(to)
        }
        if (remaining.isEmpty()) return@withContext sources.size.toULong()

        val copied = copyPaths(remaining, destination, overwrite, progress, cancel)
        deletePaths(remaining, null, cancel)
        copied
    }

    /** Permanent delete. For the reversible one use [moveToTrash]. */
    suspend fun deleteForever(
        paths: List<String>,
        progress: ProgressListener? = null,
        cancel: CancelToken? = null,
    ): ULong = withContext(io) { deletePaths(paths, progress, cancel) }

    suspend fun rename(path: String, newName: String): Boolean = withContext(io) {
        val from = File(path)
        val to = File(from.parentFile, newName)
        if (to.exists()) false else from.renameTo(to)
    }

    suspend fun createFolder(parent: String, name: String): Boolean =
        withContext(io) { File(parent, name).mkdirs() }

    /**
     * Create an empty file. Returns false if something of that name is already
     * there, rather than silently truncating it.
     */
    suspend fun createFile(parent: String, name: String): Boolean = withContext(io) {
        val target = File(parent, name)
        runCatching {
            target.parentFile?.mkdirs()
            target.createNewFile()
        }.getOrDefault(false)
    }

    // --- Category home screen ------------------------------------------------

    suspend fun byCategory(
        root: String,
        category: FileCategory,
        limit: UInt = 0u,
        cancel: CancelToken? = null,
    ): List<FileEntry> = withContext(io) { filesInCategory(root, category, limit, cancel) }

    suspend fun recent(
        root: String,
        days: UInt = 7u,
        limit: UInt = 50u,
        cancel: CancelToken? = null,
    ): List<FileEntry> = withContext(io) { recentFiles(root, days, limit, cancel) }

    // --- Search --------------------------------------------------------------

    /**
     * Note the fully qualified call.
     *
     * This method and the generated binding share the name `search` and the
     * same signature, and a member always wins over an imported top-level
     * function - so an unqualified `search(...)` here calls itself and
     * recurses until the stack blows. It compiles perfectly cleanly, so
     * nothing catches it until the app crashes on the first search.
     */
    suspend fun search(
        roots: List<String>,
        filter: SearchFilter,
        sort: SortOptions,
        progress: ProgressListener? = null,
        cancel: CancelToken? = null,
    ): List<FileEntry> = withContext(io) {
        uniffi.filemanager_core.search(roots, filter, sort, progress, cancel)
    }

    /**
     * Search, delivering matches to [sink] while the walk is still running.
     *
     * Prefer this over [search] anywhere results are displayed: [search]
     * returns only once the whole device has been walked, so the screen shows
     * nothing at all until the slowest part of the work is finished. This
     * suspends until the walk ends, with every result arriving through the
     * sink in the meantime.
     *
     * The sink is called from Rust worker threads, not the caller's - whatever
     * it touches has to be safe for that.
     */
    suspend fun searchStreaming(
        roots: List<String>,
        filter: SearchFilter,
        sink: SearchSink,
        cancel: CancelToken? = null,
    ) = withContext(io) {
        // Fully qualified for the same reason as `search` above: this member
        // and the generated binding share a name, a member always wins over a
        // top-level function, and the unqualified call would therefore invoke
        // itself and recurse until the stack blows - compiling perfectly
        // cleanly on the way.
        uniffi.filemanager_core.searchStreaming(roots, filter, sink, cancel)
    }

    // --- Storage analysis ----------------------------------------------------

    suspend fun summary(root: String, cancel: CancelToken? = null): StorageSummary =
        withContext(io) { storageSummary(root, cancel) }

    /**
     * The category breakdown and the biggest files, from a single walk.
     *
     * Prefer this over calling [summary] and [largest] in sequence: those
     * traverse the whole device once each, single threaded, so the storage
     * screen used to pay for two full walks back to back. This one is
     * parallel across the root's children and measured ~3.6x faster over
     * 320k files.
     */
    suspend fun analyze(
        root: String,
        largestLimit: UInt = 50u,
        progress: ProgressListener? = null,
        cancel: CancelToken? = null,
    ): StorageAnalysis = withContext(io) { analyzeStorage(root, largestLimit, progress, cancel) }

    suspend fun largest(root: String, limit: UInt = 50u, cancel: CancelToken? = null): List<FileEntry> =
        withContext(io) { largestFiles(root, limit, cancel) }

    suspend fun duplicates(
        root: String,
        minSize: ULong = 1024uL * 100uL,
        progress: ProgressListener? = null,
        cancel: CancelToken? = null,
    ): List<DuplicateGroup> = withContext(io) { findDuplicates(root, minSize, progress, cancel) }

    // --- Archives ------------------------------------------------------------

    suspend fun listArchive(path: String): List<ArchiveEntry> =
        withContext(io) { archiveList(path) }

    suspend fun compress(
        sources: List<String>,
        destination: String,
        progress: ProgressListener? = null,
        cancel: CancelToken? = null,
    ): ULong = withContext(io) { archiveCreate(sources, destination, progress, cancel) }

    suspend fun extract(
        archive: String,
        destination: String,
        progress: ProgressListener? = null,
        cancel: CancelToken? = null,
    ): ULong = withContext(io) { archiveExtract(archive, destination, progress, cancel) }

    // --- Trash ---------------------------------------------------------------

    suspend fun moveToTrash(paths: List<String>): List<String> =
        withContext(io) { trashMoveMany(trashDir, paths) }

    suspend fun trashContents(): List<TrashItem> =
        withContext(io) { trashList(trashDir, TRASH_RETENTION_DAYS) }

    suspend fun restoreFromTrash(id: String): String =
        withContext(io) { trashRestore(trashDir, id) }

    suspend fun restoreAll(ids: List<String>) = withContext(io) {
        ids.forEach { trashRestore(trashDir, it) }
    }

    suspend fun emptyTrash(): UInt = withContext(io) { trashEmpty(trashDir) }

    suspend fun trashBytes(): ULong = withContext(io) { trashSize(trashDir) }

    /** Called once on launch so expired items do not pile up forever. */
    suspend fun purgeExpiredTrash(): UInt =
        withContext(io) { trashPurgeExpired(trashDir, TRASH_RETENTION_DAYS) }

    // --- Formatting ----------------------------------------------------------

    /** Delegates to Rust so every screen formats sizes identically. */
    fun humanSize(bytes: ULong): String = formatSize(bytes)

    companion object {
        /** Matches Samsung's 30-day recycle bin. */
        const val TRASH_RETENTION_DAYS: UInt = 30u

        private const val TRASH_FOLDER = ".FileManagerTrash"

        /**
         * Primary storage root if it is writable, the app's own directory
         * otherwise.
         *
         * The fallback keeps the trash working when all-files access has not
         * been granted; deletes are slower there, but they happen.
         */
        private fun resolveTrashDir(context: Context): String {
            val external = Environment.getExternalStorageDirectory()
            if (external != null && external.canWrite()) {
                return File(external, TRASH_FOLDER).absolutePath
            }
            return File(context.getExternalFilesDir(null), "trash").absolutePath
        }
    }
}

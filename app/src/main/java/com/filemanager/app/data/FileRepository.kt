package com.filemanager.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.filemanager_core.ArchiveEntry
import uniffi.filemanager_core.CancelToken
import uniffi.filemanager_core.DuplicateGroup
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.ProgressListener
import uniffi.filemanager_core.SearchFilter
import uniffi.filemanager_core.SortOptions
import uniffi.filemanager_core.StorageSummary
import uniffi.filemanager_core.TrashItem
import uniffi.filemanager_core.TreeStats
import uniffi.filemanager_core.archiveCreate
import uniffi.filemanager_core.archiveExtract
import uniffi.filemanager_core.archiveList
import uniffi.filemanager_core.copyPaths
import uniffi.filemanager_core.deletePaths
import uniffi.filemanager_core.dirSize
import uniffi.filemanager_core.filesInCategory
import uniffi.filemanager_core.findDuplicates
import uniffi.filemanager_core.formatSize
import uniffi.filemanager_core.largestFiles
import uniffi.filemanager_core.listDir
import uniffi.filemanager_core.recentFiles
import uniffi.filemanager_core.search
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
     * Where trashed files live: the app's own external files directory.
     *
     * Deliberately not a hidden folder on shared storage. This one is removed
     * when the app is uninstalled and needs no extra permission, and nothing
     * else on the device scans it.
     */
    private val trashDir: String =
        File(appContext.getExternalFilesDir(null), "trash").absolutePath

    // --- Browsing -----------------------------------------------------------

    suspend fun list(path: String, showHidden: Boolean, sort: SortOptions): List<FileEntry> =
        withContext(io) { listDir(path, showHidden, sort) }

    suspend fun directorySize(path: String, cancel: CancelToken? = null): ULong =
        withContext(io) { dirSize(path, cancel) }

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

    suspend fun search(
        roots: List<String>,
        filter: SearchFilter,
        sort: SortOptions,
        progress: ProgressListener? = null,
        cancel: CancelToken? = null,
    ): List<FileEntry> = withContext(io) { search(roots, filter, sort, progress, cancel) }

    // --- Storage analysis ----------------------------------------------------

    suspend fun summary(root: String, cancel: CancelToken? = null): StorageSummary =
        withContext(io) { storageSummary(root, cancel) }

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
    }
}

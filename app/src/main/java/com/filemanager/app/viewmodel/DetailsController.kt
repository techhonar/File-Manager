package com.filemanager.app.viewmodel

import com.filemanager.app.data.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.FileEntry

/**
 * The parts of a file's details that have to be fetched.
 *
 * Everything else comes straight off the FileEntry; these three need either a
 * subtree walk or a MediaStore query, so the sheet shows what it has and fills
 * these in when they land.
 */
data class FileDetails(
    val folderBytes: ULong? = null,
    val fileCount: ULong? = null,
    val folderCount: ULong? = null,
    val ownerApp: String? = null,
)

/**
 * The details sheet, for one file or folder or for several together:
 * [details] is null until they come back.
 */
data class DetailsSheet(val entries: List<FileEntry>, val details: FileDetails? = null) {
    /** The one item, when the sheet is about one. */
    val entry: FileEntry? get() = entries.singleOrNull()
}

/**
 * The details sheet, for every screen that can open one - a folder, search,
 * recent files - so it says the same everywhere.
 *
 * It opens at once with what the entry already says, and fills in what takes
 * longer: a folder's size and contents need a walk of everything under it,
 * and the app a file came from is a MediaStore query. [ownerAppOf] is passed
 * in because it needs a Context, and a view model holding one leaks it.
 */
class DetailsController(
    private val repository: FileRepository,
    private val ownerAppOf: suspend (String) -> String?,
    private val scope: CoroutineScope,
) {
    private val _sheet = MutableStateFlow<DetailsSheet?>(null)

    /** Null while no sheet is open. */
    val sheet: StateFlow<DetailsSheet?> = _sheet.asStateFlow()

    fun show(entry: FileEntry) = show(listOf(entry))

    /**
     * Several together, as One UI sums a selection up: how many, their size
     * with everything in their folders, and what they hold.
     */
    fun show(entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        _sheet.value = DetailsSheet(entries)
        val single = entries.singleOrNull()
        scope.launch {
            // One walk covers size and contents; tree_stats returns both, so
            // asking for them separately would traverse twice. Only for
            // folders: a file's size is on its entry already.
            val stats = if (entries.any { it.isDir }) {
                runCatching { repository.stats(entries.map { it.path }) }.getOrNull()
            } else {
                null
            }
            // Which app a file came from means something for one file only.
            val owner = single?.let { runCatching { ownerAppOf(it.path) }.getOrNull() }

            _sheet.update { current ->
                // Discard a result that arrives after the sheet moved on.
                if (current?.entries != entries) return@update current
                current.copy(
                    details = FileDetails(
                        folderBytes = stats?.totalBytes,
                        fileCount = stats?.fileCount,
                        // The walk counts each folder it starts from. One
                        // folder's sheet asks what is inside it; a
                        // selection's counts the folders chosen too.
                        folderCount = stats?.dirCount?.let {
                            if (single != null && it > 0uL) it - 1uL else it
                        },
                        ownerApp = owner,
                    ),
                )
            }
        }
    }

    fun dismiss() {
        _sheet.value = null
    }
}

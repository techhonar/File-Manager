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

/** One file or folder's details sheet: [details] is null until they come back. */
data class DetailsSheet(val entry: FileEntry, val details: FileDetails? = null)

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

    fun show(entry: FileEntry) {
        _sheet.value = DetailsSheet(entry)
        scope.launch {
            // One walk covers size and contents; tree_stats returns both, so
            // asking for them separately would traverse twice.
            val stats = if (entry.isDir) {
                runCatching { repository.stats(listOf(entry.path)) }.getOrNull()
            } else {
                null
            }
            val owner = runCatching { ownerAppOf(entry.path) }.getOrNull()

            _sheet.update { current ->
                // Discard a result that arrives after the sheet moved on.
                if (current?.entry?.path != entry.path) return@update current
                current.copy(
                    details = FileDetails(
                        folderBytes = stats?.totalBytes,
                        fileCount = stats?.fileCount,
                        // The walk counts the folder it starts from; what the
                        // sheet asks is how many folders are inside it.
                        folderCount = stats?.dirCount?.let { if (it > 0uL) it - 1uL else 0uL },
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

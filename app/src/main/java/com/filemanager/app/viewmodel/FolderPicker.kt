package com.filemanager.app.viewmodel

import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.SortKey
import uniffi.filemanager_core.SortOptions
import java.io.File

/**
 * The folder picker's state.
 *
 * Its own state rather than a path typed into a field: the path has to exist,
 * and a person typing /storage/emulated/0/DCIM by hand gets it wrong more
 * often than not - usually by guessing "sdcard" or a capital letter.
 */
data class FolderPickerState(
    val path: String,
    val folders: List<FileEntry> = emptyList(),
    val isLoading: Boolean = true,
    /** False at a volume root. Going above one reaches directories the app
     *  cannot read, which would look like an empty folder rather than a wall. */
    val canGoUp: Boolean = false,
    val error: String? = null,
)

/**
 * Walking to a folder, for whatever needs one chosen: the folder the FTP
 * server shares, or where an archive is extracted.
 *
 * [volumeRoots] bound how far up it goes. [scope] is the owner's, so a
 * listing in flight stops with the screen that asked for it.
 */
class FolderPicker(
    private val repository: FileRepository,
    private val volumeRoots: List<String>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<FolderPickerState?>(null)

    /** Null while the picker is closed. */
    val state: StateFlow<FolderPickerState?> = _state.asStateFlow()

    /** Open the picker at [path]. */
    fun open(path: String) = browseTo(path)

    /** Go into [path], one of the folders on show. */
    fun enter(path: String) = browseTo(path)

    fun up() {
        val current = _state.value ?: return
        if (!current.canGoUp) return
        browseTo(File(current.path).parent ?: return)
    }

    /** Take the folder on show, closing the picker. Null if it was not open. */
    fun choose(): String? = _state.value?.path.also { close() }

    fun close() {
        _state.value = null
    }

    private fun browseTo(path: String) {
        _state.value = FolderPickerState(path = path, isLoading = true, canGoUp = canGoUp(path))
        scope.launch {
            runCatching { repository.list(path, showHidden = false, sort = FOLDER_SORT) }
                .onSuccess { entries ->
                    _state.update { current ->
                        // Ignore a listing that came back after the user moved
                        // on, which over a slow card is not hypothetical.
                        if (current?.path != path) return@update current
                        // Only folders: this picks somewhere, and listing the
                        // files as well would bury the folders in a full
                        // camera roll.
                        current.copy(folders = entries.filter { it.isDir }, isLoading = false, error = null)
                    }
                }
                .onFailure { failure ->
                    _state.update { current ->
                        if (current?.path != path) return@update current
                        current.copy(
                            isLoading = false,
                            // userMessage: this listing comes from the core,
                            // whose errors often have an empty message - and
                            // `?:` catches null, not "".
                            error = failure.userMessage("Could not open that folder"),
                        )
                    }
                }
        }
    }

    /** False at a volume root, and anywhere outside one. */
    private fun canGoUp(path: String): Boolean {
        val normalised = File(path).absolutePath
        if (volumeRoots.any { it == normalised }) return false
        return volumeRoots.any { normalised.startsWith("$it/") }
    }

    private companion object {
        val FOLDER_SORT = SortOptions(SortKey.NAME, descending = false, dirsFirst = true)
    }
}

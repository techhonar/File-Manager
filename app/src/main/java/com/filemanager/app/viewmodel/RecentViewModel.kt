package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.AppSettings
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.PathPrefs
import com.filemanager.app.data.ViewScope
import com.filemanager.app.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.CancelToken
import uniffi.filemanager_core.FileEntry
import java.io.File

data class RecentState(
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = true,
    val selected: Set<String> = emptySet(),
    val selectionActive: Boolean = false,
    val message: String? = null,
    val viewMode: ViewMode = ViewMode.LIST,
) {
    val inSelectionMode: Boolean get() = selectionActive || selected.isNotEmpty()
    val allSelected: Boolean
        get() = entries.isNotEmpty() && selected.size == entries.size
}

/**
 * Recent files, with the same selection and actions as everywhere else.
 *
 * It has its own view model rather than borrowing the home screen's, which
 * held the list read-only - so the rows could be looked at and opened but not
 * acted on, unlike every other list in the app.
 */
class RecentViewModel(
    private val repository: FileRepository,
    private val clipboard: FileClipboard,
    private val rootPath: String,
    private val settings: AppSettings,
    /** Favourites and pins, which a rename has to carry to the new name. */
    private val paths: PathPrefs,
    /** See BrowserViewModel: passed in so no view model holds a Context. */
    ownerAppOf: suspend (String) -> String? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow(
        RecentState(viewMode = settings.viewMode(ViewScope.Recent).value.toViewMode()),
    )
    val state: StateFlow<RecentState> = _state.asStateFlow()

    private var scan: CancelToken? = null

    /** The details sheet, opened from the selection's More menu. */
    val details = DetailsController(repository, ownerAppOf, viewModelScope)

    /** Which paths are favourites, so the menu can offer the opposite. */
    val favorites: StateFlow<Set<String>> = paths.favorites

    init {
        refresh()
    }

    fun refresh() {
        scan?.cancel()
        val token = CancelToken()
        scan = token

        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            runCatching { repository.recent(rootPath, days = 7u, limit = 200u, cancel = token) }
                .onSuccess { entries ->
                    // A scan that has since been replaced says nothing about
                    // whether the current one is still loading.
                    if (scan !== token) return@onSuccess
                    _state.update { it.copy(entries = entries, isLoading = false) }
                }
                .onFailure {
                    // Cancellation lands here too, which is the expected path
                    // when the user leaves the screen.
                    if (scan !== token) return@onFailure
                    _state.update { it.copy(isLoading = false) }
                }
        }
    }

    /** Kept for next time, apart from every other list's: see ViewScope. */
    fun setViewMode(mode: ViewMode) {
        settings.setViewMode(ViewScope.Recent, mode.toSetting())
        _state.update { it.copy(viewMode = mode) }
    }

    fun enterSelectionMode() = _state.update { it.copy(selectionActive = true) }

    fun toggleSelection(path: String) = _state.update { current ->
        val next = current.selected.toMutableSet()
        if (!next.add(path)) next.remove(path)
        current.copy(selected = next)
    }

    fun toggleSelectAll() = _state.update { current ->
        if (current.allSelected) current.copy(selected = emptySet())
        else current.copy(selected = current.entries.map { it.path }.toSet())
    }

    fun clearSelection() =
        _state.update { it.copy(selected = emptySet(), selectionActive = false) }

    fun copySelection() {
        clipboard.copy(_state.value.selected.toList())
        // Out of selection mode as well, or the screen stayed in it with
        // nothing ticked when selection had been entered from the menu.
        _state.update {
            it.copy(
                selected = emptySet(),
                selectionActive = false,
                message = "Copied. Paste in any folder.",
            )
        }
    }

    fun cutSelection() {
        clipboard.cut(_state.value.selected.toList())
        _state.update {
            it.copy(
                selected = emptySet(),
                selectionActive = false,
                message = "Cut. Paste in any folder.",
            )
        }
    }

    fun deleteSelection() {
        val paths = _state.value.selected.toList()
        if (paths.isEmpty()) return

        viewModelScope.launch {
            runCatching { repository.moveToTrash(paths) }
                .onSuccess {
                    val gone = paths.toSet()
                    _state.update { current ->
                        current.copy(
                            entries = current.entries.filterNot { it.path in gone },
                            selected = emptySet(),
                            selectionActive = false,
                            message = "${paths.size} moved to trash",
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(message = e.userMessage("Could not delete")) }
                }
        }
    }

    /** Favourite the selection, or unfavourite it when it all is already. */
    fun toggleFavorite() {
        val selected = _state.value.selected.toList()
        if (selected.isEmpty()) return
        paths.toggleFavorite(selected)
        val message = if (selected.all { paths.isFavorite(it) }) "Added to favourites" else "Removed from favourites"
        _state.update { it.copy(selected = emptySet(), selectionActive = false, message = message) }
    }

    fun rename(path: String, newName: String) {
        viewModelScope.launch {
            val newPath = File(File(path).parentFile, newName).absolutePath
            val ok = runCatching { repository.rename(path, newName) }.getOrDefault(false)
            if (!ok) {
                // Only claim a clash when there is one: a rename can also be
                // refused for want of permission.
                val message = if (File(newPath).exists()) {
                    "A file named \"$newName\" already exists"
                } else {
                    "Could not rename \"${File(path).name}\""
                }
                _state.update { it.copy(message = message) }
                return@launch
            }
            paths.move(path, newPath)
            // Swapped in where it was: a rename leaves when a file was last
            // changed alone, so it keeps its place in the list.
            val renamed = runCatching { repository.entriesFor(listOf(newPath)) }.getOrNull()?.firstOrNull()
            _state.update { current ->
                current.copy(
                    entries = if (renamed != null) {
                        current.entries.map { if (it.path == path) renamed else it }
                    } else {
                        current.entries.filterNot { it.path == path }
                    },
                )
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        scan?.cancel()
        super.onCleared()
    }
}

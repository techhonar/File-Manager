package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.PathPrefs
import com.filemanager.app.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.filemanager.app.data.AppSettings
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.FileEntry
import java.io.File

data class FavoritesState(
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = true,
    val missingCount: Int = 0,
    val selected: Set<String> = emptySet(),
    val selectionActive: Boolean = false,
    val message: String? = null,
    /**
     * Whether hidden favourites are listed.
     *
     * Marking a favourite as hidden used to remove it from this screen with no
     * way to see it again - the file was still favourited, but the only switch
     * that would show it lived in the browser's menu, on a different screen.
     */
    val showHidden: Boolean = false,
    /** Favourites that exist but are filtered out by [showHidden]. */
    val hiddenCount: Int = 0,
) {
    val inSelectionMode: Boolean get() = selectionActive || selected.isNotEmpty()
    val allSelected: Boolean
        get() = entries.isNotEmpty() && selected.size == entries.size
}

/**
 * The favourites list, resolved from stored paths each time it is shown.
 *
 * Resolved rather than cached because the files behind those paths can be
 * moved or deleted by anything at any time - so the marks are reconciled here,
 * and any that no longer point at a file are dropped rather than shown as
 * broken rows.
 */
class FavoritesViewModel(
    private val repository: FileRepository,
    private val paths: PathPrefs,
    private val settings: AppSettings,
    /** Whether the storage a path is on is present. Passed in so no
     *  ViewModel reaches for the framework; see StorageVolumes.isMounted. */
    private val isMounted: (String) -> Boolean,
    /** See BrowserViewModel: passed in so no view model holds a Context. */
    ownerAppOf: suspend (String) -> String? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesState())
    val state: StateFlow<FavoritesState> = _state.asStateFlow()

    /** The details sheet, opened from the selection's More menu. */
    val details = DetailsController(repository, ownerAppOf, viewModelScope)

    init {
        viewModelScope.launch {
            // Re-resolve when either the marks or the hidden-files preference
            // changes: a favourite that has just been hidden should disappear
            // from here too, which it did not before.
            // The ordered list, not the set: a drag changes only the order.
            combine(paths.favoriteOrder, settings.showHidden) { favorites, showHidden ->
                favorites to showHidden
            }.collect { (favorites, showHidden) ->
                _state.update { it.copy(showHidden = showHidden) }
                resolve(favorites, showHidden)
            }
        }
    }

    /** Show or hide the hidden favourites. Shared with every other list. */
    fun toggleShowHidden() = settings.setShowHidden(!_state.value.showHidden)

    private suspend fun resolve(favorites: List<String>, showHidden: Boolean) {
        if (favorites.isEmpty()) {
            _state.value = FavoritesState(isLoading = false, showHidden = showHidden)
            return
        }
        val resolved = repository.entriesFor(favorites)

        // Resolved, not shown. A hidden favourite still exists - it is only
        // filtered out of the list below - and counting it as missing here
        // dropped the mark, so turning hidden files back on showed nothing and
        // the favourite was gone for good.
        val found = resolved.map { it.path }.toSet()
        val missing = favorites.toSet() - found

        // Forget the ones that have gone, so the list does not keep shrinking
        // silently every time it is opened - but only from storage that is
        // actually there. With the SD card out every favourite on it looks
        // deleted, and forgetting them then meant they were gone for good
        // once the card went back in.
        val gone = missing.filter(isMounted)
        if (gone.isNotEmpty()) paths.forget(gone)

        // In the user's order, which the favourites themselves keep.
        val place = favorites.withIndex().associate { (index, path) -> path to index }
        val visible = resolved.filter { showHidden || !it.isHidden }.sortedBy { place[it.path] }
        val shown = visible.map { it.path }.toSet()

        _state.update { current ->
            current.copy(
                entries = visible,
                isLoading = false,
                missingCount = gone.size,
                hiddenCount = resolved.size - visible.size,
                // Drop any tick whose file is no longer on screen, so the
                // count in the title matches what can be acted on.
                selected = current.selected.intersect(shown),
            )
        }
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

    /**
     * Remove the selection from favourites.
     *
     * Only the mark goes - the files are left alone. Deleting from a list of
     * favourites would be a surprising thing for it to do.
     */
    fun removeSelected() {
        val selected = _state.value.selected.toList()
        if (selected.isEmpty()) return

        paths.toggleFavorite(selected)
        _state.update { current ->
            current.copy(
                entries = current.entries.filterNot { it.path in selected.toSet() },
                selected = emptySet(),
                selectionActive = false,
                message = "Removed ${selected.size} from favourites",
            )
        }
    }

    /**
     * Keep the favourites in [paths]' order: a row dragged to a new place.
     * Shown at once, so the list does not jump back while it is stored.
     */
    fun reorder(paths: List<String>) {
        _state.update { current ->
            val byPath = current.entries.associateBy { it.path }
            current.copy(entries = paths.mapNotNull(byPath::get))
        }
        this.paths.reorderFavorites(paths)
    }

    /** The selected files, to the trash. Their marks go once they are gone. */
    fun deleteSelection() {
        val selected = _state.value.selected.toList()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.moveToTrash(selected) }
                .onSuccess {
                    paths.forget(selected)
                    _state.update {
                        it.copy(selected = emptySet(), selectionActive = false, message = "${selected.size} moved to trash")
                    }
                }
                .onFailure { e -> _state.update { it.copy(message = e.userMessage("Could not delete")) } }
        }
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
            // Its mark goes with it, in the same place; the list follows the
            // marks, so it is shown under its new name there.
            paths.move(path, newPath)
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

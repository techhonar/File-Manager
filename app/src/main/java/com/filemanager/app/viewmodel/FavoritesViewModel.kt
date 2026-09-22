package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.PathPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.filemanager.app.data.AppSettings
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.FileEntry

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
) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesState())
    val state: StateFlow<FavoritesState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Re-resolve when either the marks or the hidden-files preference
            // changes: a favourite that has just been hidden should disappear
            // from here too, which it did not before.
            combine(paths.favorites, settings.showHidden) { favorites, showHidden ->
                favorites to showHidden
            }.collect { (favorites, showHidden) ->
                _state.update { it.copy(showHidden = showHidden) }
                resolve(favorites, showHidden)
            }
        }
    }

    /** Show or hide the hidden favourites. Shared with every other list. */
    fun toggleShowHidden() = settings.setShowHidden(!_state.value.showHidden)

    private suspend fun resolve(favorites: Set<String>, showHidden: Boolean) {
        if (favorites.isEmpty()) {
            _state.value = FavoritesState(isLoading = false, showHidden = showHidden)
            return
        }
        val resolved = repository.entriesFor(favorites.toList())

        // Resolved, not shown. A hidden favourite still exists - it is only
        // filtered out of the list below - and counting it as missing here
        // dropped the mark, so turning hidden files back on showed nothing and
        // the favourite was gone for good.
        val found = resolved.map { it.path }.toSet()
        val missing = favorites - found

        // Forget the ones that have gone, so the list does not keep shrinking
        // silently every time it is opened - but only from storage that is
        // actually there. With the SD card out every favourite on it looks
        // deleted, and forgetting them then meant they were gone for good
        // once the card went back in.
        val gone = missing.filter(isMounted)
        if (gone.isNotEmpty()) paths.forget(gone)

        val visible = resolved.filter { showHidden || !it.isHidden }
        val shown = visible.map { it.path }.toSet()

        _state.update { current ->
            current.copy(
                entries = visible.sortedBy { it.name.lowercase() },
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

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

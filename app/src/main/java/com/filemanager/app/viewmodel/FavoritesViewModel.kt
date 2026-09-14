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
            }.collect { (favorites, showHidden) -> resolve(favorites, showHidden) }
        }
    }

    private suspend fun resolve(favorites: Set<String>, showHidden: Boolean) {
        if (favorites.isEmpty()) {
            _state.value = FavoritesState(isLoading = false)
            return
        }
        val entries = repository.entriesFor(favorites.toList())
            .filter { showHidden || !it.isHidden }
        val found = entries.map { it.path }.toSet()
        val missing = favorites - found

        // Forget the ones that have gone, so the list does not keep shrinking
        // silently every time it is opened.
        if (missing.isNotEmpty()) paths.forget(missing)

        _state.update { current ->
            current.copy(
                entries = entries.sortedBy { it.name.lowercase() },
                isLoading = false,
                missingCount = missing.size,
                // Drop any tick whose file is no longer in the list.
                selected = current.selected.intersect(found),
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

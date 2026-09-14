package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.PathPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.FileEntry

data class FavoritesState(
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = true,
    val missingCount: Int = 0,
)

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
) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesState())
    val state: StateFlow<FavoritesState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            paths.favorites.collect { favorites -> resolve(favorites) }
        }
    }

    private suspend fun resolve(favorites: Set<String>) {
        if (favorites.isEmpty()) {
            _state.value = FavoritesState(isLoading = false)
            return
        }
        val entries = repository.entriesFor(favorites.toList())
        val found = entries.map { it.path }.toSet()
        val missing = favorites - found

        // Forget the ones that have gone, so the list does not keep shrinking
        // silently every time it is opened.
        if (missing.isNotEmpty()) paths.forget(missing)

        _state.value = FavoritesState(
            entries = entries.sortedBy { it.name.lowercase() },
            isLoading = false,
            missingCount = missing.size,
        )
    }

    fun remove(path: String) {
        paths.toggleFavorite(listOf(path))
        _state.update { it.copy(entries = it.entries.filterNot { e -> e.path == path }) }
    }
}

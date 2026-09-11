package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.StorageVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.CancelToken
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry

/** One tile on the category home screen. */
data class CategoryTile(
    val category: FileCategory,
    val fileCount: ULong,
    val bytes: ULong,
)

data class HomeState(
    val volumes: List<StorageVolume> = emptyList(),
    val tiles: List<CategoryTile> = emptyList(),
    val recent: List<FileEntry> = emptyList(),
    val usedBytes: ULong = 0uL,
    val totalBytes: ULong = 0uL,
    val trashBytes: ULong = 0uL,
    val isLoading: Boolean = true,
)

class HomeViewModel(
    private val repository: FileRepository,
    private val volumes: List<StorageVolume>,
    private val primaryPath: String,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState(volumes = volumes))
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /**
     * Held so the scan can be stopped when the user leaves. A full-device
     * category scan is the most expensive thing this app does.
     */
    private var scan: CancelToken? = null

    init {
        refresh()
    }

    fun refresh() {
        scan?.cancel()
        val token = CancelToken()
        scan = token

        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            runCatching {
                // The summary walks the tree once and returns per-category
                // totals, so the tiles cost a single scan rather than one
                // per category.
                val summary = repository.summary(primaryPath, token)
                val recent = repository.recent(primaryPath, days = 7u, limit = 20u, cancel = token)
                val trash = repository.trashBytes()
                Triple(summary, recent, trash)
            }.onSuccess { (summary, recent, trash) ->
                val tiles = TILE_ORDER.mapNotNull { category ->
                    summary.byCategory.firstOrNull { it.category == category }
                        ?.let { CategoryTile(category, it.fileCount, it.bytes) }
                }
                _state.update {
                    it.copy(
                        tiles = tiles,
                        recent = recent,
                        usedBytes = summary.totalBytes - summary.freeBytes,
                        totalBytes = summary.totalBytes,
                        trashBytes = trash,
                        isLoading = false,
                    )
                }
            }.onFailure {
                // A cancelled scan is the expected path when the user
                // navigates away, not an error worth showing.
                _state.update { state -> state.copy(isLoading = false) }
            }
        }
    }

    override fun onCleared() {
        scan?.cancel()
        super.onCleared()
    }

    private companion object {
        /** Tile order on screen, matching Samsung's My Files layout. */
        val TILE_ORDER = listOf(
            FileCategory.IMAGE,
            FileCategory.VIDEO,
            FileCategory.AUDIO,
            FileCategory.DOCUMENT,
            FileCategory.APK,
            FileCategory.ARCHIVE,
        )
    }
}

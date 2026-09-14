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

/** Capacity figures for one mounted volume, keyed by its path. */
data class VolumeUsage(val usedBytes: ULong, val totalBytes: ULong)

data class HomeState(
    val volumes: List<StorageVolume> = emptyList(),
    val tiles: List<CategoryTile> = emptyList(),
    val recent: List<FileEntry> = emptyList(),
    /** Per-volume capacity, so each storage row can show its own gauge. */
    val volumeUsage: Map<String, VolumeUsage> = emptyMap(),
    val usedBytes: ULong = 0uL,
    val totalBytes: ULong = 0uL,
    val trashBytes: ULong = 0uL,
    /** False on a phone with no card slot, where the SD row is just clutter. */
    val hasRemovableSlot: Boolean = false,
    val isLoading: Boolean = true,
)

class HomeViewModel(
    private val repository: FileRepository,
    private val volumes: List<StorageVolume>,
    private val primaryPath: String,
    hasRemovableSlot: Boolean = false,
) : ViewModel() {

    private val _state =
        MutableStateFlow(HomeState(volumes = volumes, hasRemovableSlot = hasRemovableSlot))
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

        // statvfs per volume is near-instant, so publish the storage gauges
        // straight away rather than making them wait on the category scan.
        viewModelScope.launch {
            val usage = volumes.mapNotNull { volume ->
                runCatching { repository.volumeStats(volume.path) }.getOrNull()
                    ?.let { stats ->
                        volume.path to VolumeUsage(
                            usedBytes = stats.totalBytes - stats.freeBytes,
                            totalBytes = stats.totalBytes,
                        )
                    }
            }.toMap()
            _state.update { it.copy(volumeUsage = usage) }
        }

        viewModelScope.launch {
            runCatching {
                // Deliberately no category summary here. It walked the entire
                // device on every launch to fill `tiles`, and the home screen
                // shows fixed tiles with no per-category figures - so the work
                // was paid for and then thrown away. The storage screen still
                // computes it, on demand, where it is actually displayed.
                val recent = repository.recent(primaryPath, days = 7u, limit = 20u, cancel = token)
                val trash = repository.trashBytes()
                recent to trash
            }.onSuccess { (recent, trash) ->
                _state.update {
                    it.copy(
                        recent = recent,
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

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
import uniffi.filemanager_core.FileCategory

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
    /** Per-volume capacity, so each storage row can show its own gauge. */
    val volumeUsage: Map<String, VolumeUsage> = emptyMap(),
    val usedBytes: ULong = 0uL,
    val totalBytes: ULong = 0uL,
    val trashBytes: ULong = 0uL,
    /** False on a phone with no card slot, where the SD row is just clutter. */
    val hasRemovableSlot: Boolean = false,
)

class HomeViewModel(
    private val repository: FileRepository,
    private val volumes: List<StorageVolume>,
    hasRemovableSlot: Boolean = false,
) : ViewModel() {

    private val _state =
        MutableStateFlow(HomeState(volumes = volumes, hasRemovableSlot = hasRemovableSlot))
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        // The screen also asks on every resume, including the first, so this
        // usually runs twice on the way in. Kept anyway: if the screen ever
        // stopped asking, Home would come up without its figures, and both
        // are cheap.
        refresh()
    }

    /**
     * The storage gauges and the size of the trash - everything Home shows
     * that can change.
     *
     * No walk of the device any more. Home listed the newest recent files,
     * which meant scanning storage for them every time it came into view;
     * it now has a row that opens Recent, which finds them itself.
     */
    fun refresh() {
        // statvfs per volume is near-instant, so the gauges fill in at once.
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

        // Deliberately no category summary here either. It walked the entire
        // device on every launch to fill `tiles`, and the home screen shows
        // fixed tiles with no per-category figures - so the work was paid for
        // and then thrown away. The storage screen still computes it, on
        // demand, where it is actually displayed.
        viewModelScope.launch {
            runCatching { repository.trashBytes() }
                .onSuccess { trash -> _state.update { it.copy(trashBytes = trash) } }
        }
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

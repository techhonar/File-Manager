package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.CancelToken
import uniffi.filemanager_core.DuplicateGroup
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.ProgressListener
import uniffi.filemanager_core.StorageSummary
import uniffi.filemanager_core.formatSize

data class StorageState(
    val summary: StorageSummary? = null,
    val largest: List<FileEntry> = emptyList(),
    val duplicates: List<DuplicateGroup> = emptyList(),
    val isLoading: Boolean = true,
    val isScanningDuplicates: Boolean = false,
    val duplicateProgress: String = "",
    /** Paths ticked in the largest-files list. */
    val selected: Set<String> = emptySet(),
    val selectionActive: Boolean = false,
    val message: String? = null,
) {
    /** Bytes recoverable by removing every duplicate but one. */
    val reclaimable: ULong get() = duplicates.fold(0uL) { sum, group -> sum + group.wastedBytes }

    val inSelectionMode: Boolean get() = selectionActive || selected.isNotEmpty()

    val allSelected: Boolean
        get() = largest.isNotEmpty() && selected.size == largest.size

    /** Space that deleting the current selection would free. */
    val selectedBytes: ULong
        get() = largest.filter { it.path in selected }.fold(0uL) { sum, e -> sum + e.size }
}

class StorageViewModel(
    private val repository: FileRepository,
    private val rootPath: String,
) : ViewModel() {

    // --- Selection ----------------------------------------------------------
    //
    // This screen exists to free space, so being able to look at the biggest
    // files without being able to act on them was the wrong half of the job.

    fun enterSelectionMode() = _state.update { it.copy(selectionActive = true) }

    fun toggleSelection(path: String) = _state.update { current ->
        val next = current.selected.toMutableSet()
        if (!next.add(path)) next.remove(path)
        current.copy(selected = next)
    }

    fun toggleSelectAll() = _state.update { current ->
        if (current.allSelected) {
            current.copy(selected = emptySet())
        } else {
            current.copy(selected = current.largest.map { it.path }.toSet())
        }
    }

    fun clearSelection() =
        _state.update { it.copy(selected = emptySet(), selectionActive = false) }

    /** Delete goes through the trash, so a mistake here is recoverable. */
    fun deleteSelection() {
        val paths = _state.value.selected.toList()
        if (paths.isEmpty()) return
        val freed = _state.value.selectedBytes

        viewModelScope.launch {
            runCatching { repository.moveToTrash(paths) }
                .onSuccess {
                    val gone = paths.toSet()
                    _state.update { current ->
                        current.copy(
                            // Drop them from the list rather than re-running
                            // the whole walk for a handful of removals.
                            largest = current.largest.filterNot { it.path in gone },
                            selected = emptySet(),
                            selectionActive = false,
                            message = "${paths.size} moved to trash, ${formatSize(freed)} freed",
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(message = e.userMessage("Could not delete")) }
                }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private val _state = MutableStateFlow(StorageState())
    val state: StateFlow<StorageState> = _state.asStateFlow()

    private var scan: CancelToken? = null

    init {
        load()
    }

    fun load() {
        scan?.cancel()
        val token = CancelToken()
        scan = token

        _state.update { it.copy(isLoading = true) }

        // Capacity is one statvfs call and returns immediately, so the screen
        // can draw its real layout - total, free, the used figure - while the
        // walk that fills in the per-category breakdown is still running.
        // Waiting for the whole scan meant staring at a spinner for a minute
        // to learn something the system already knew.
        viewModelScope.launch {
            runCatching { repository.volumeStats(rootPath) }.getOrNull()?.let { fs ->
                _state.update { current ->
                    if (current.summary != null) return@update current
                    current.copy(
                        summary = StorageSummary(
                            totalBytes = fs.totalBytes,
                            freeBytes = fs.freeBytes,
                            // Not yet known; the categories fill in below.
                            scannedBytes = 0uL,
                            byCategory = emptyList(),
                        ),
                    )
                }
            }
        }

        viewModelScope.launch {
            // No progress listener: the screen no longer reports a file count,
            // and a callback firing every few hundred files to update state
            // nothing reads is a JNI hop for nothing.
            runCatching { repository.analyze(rootPath, 50u, null, token) }
                .onSuccess { analysis ->
                    _state.update {
                        it.copy(
                            summary = StorageSummary(
                                totalBytes = analysis.totalBytes,
                                freeBytes = analysis.freeBytes,
                                scannedBytes = analysis.scannedBytes,
                                byCategory = analysis.byCategory,
                            ),
                            largest = analysis.largest,
                            isLoading = false,
                        )
                    }
                }
                .onFailure {
                    // A cancelled scan lands here too, which is the expected
                    // path when the user leaves the screen.
                    _state.update { s -> s.copy(isLoading = false) }
                }
        }
    }

    /**
     * Started only on demand, from a button.
     *
     * Duplicate detection hashes file contents, so unlike the summary it is
     * genuinely expensive -- not something to run every time the screen opens.
     */
    fun scanDuplicates() {
        scan?.cancel()
        val token = CancelToken()
        scan = token

        _state.update { it.copy(isScanningDuplicates = true, duplicates = emptyList()) }
        viewModelScope.launch {
            val progress = object : ProgressListener {
                override fun onProgress(done: ULong, total: ULong, currentPath: String) {
                    _state.update { it.copy(duplicateProgress = "Checking $done of $total groups") }
                }
            }

            runCatching { repository.duplicates(rootPath, MIN_DUPLICATE_SIZE, progress, token) }
                .onSuccess { groups ->
                    _state.update {
                        it.copy(
                            duplicates = groups,
                            isScanningDuplicates = false,
                            duplicateProgress = "",
                        )
                    }
                }
                .onFailure {
                    _state.update { s -> s.copy(isScanningDuplicates = false, duplicateProgress = "") }
                }
        }
    }

    /** Keep the first copy in each group, trash the rest. */
    fun deleteDuplicates(group: DuplicateGroup) {
        val extras = group.files.drop(1).map { it.path }
        if (extras.isEmpty()) return

        viewModelScope.launch {
            runCatching { repository.moveToTrash(extras) }
                .onSuccess {
                    _state.update { current ->
                        current.copy(duplicates = current.duplicates.filterNot { it.hash == group.hash })
                    }
                }
        }
    }

    fun cancelScan() {
        scan?.cancel()
        _state.update { it.copy(isScanningDuplicates = false, duplicateProgress = "") }
    }

    override fun onCleared() {
        scan?.cancel()
        super.onCleared()
    }

    private companion object {
        /** Ignore anything under 100 KB -- small duplicates are mostly caches. */
        val MIN_DUPLICATE_SIZE: ULong = 100uL * 1024uL
    }
}

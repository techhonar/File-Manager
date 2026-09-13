package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileRepository
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

data class StorageState(
    val summary: StorageSummary? = null,
    val largest: List<FileEntry> = emptyList(),
    val duplicates: List<DuplicateGroup> = emptyList(),
    val isLoading: Boolean = true,
    val isScanningDuplicates: Boolean = false,
    val duplicateProgress: String = "",
    /** Live count while the storage walk runs, shown under the spinner. */
    val scanProgress: String = "",
) {
    /** Bytes recoverable by removing every duplicate but one. */
    val reclaimable: ULong get() = duplicates.fold(0uL) { sum, group -> sum + group.wastedBytes }
}

class StorageViewModel(
    private val repository: FileRepository,
    private val rootPath: String,
) : ViewModel() {

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

        _state.update { it.copy(isLoading = true, scanProgress = "") }
        viewModelScope.launch {
            // A whole-device walk takes real time, so report progress rather
            // than showing a spinner that says nothing for a minute.
            val progress = object : ProgressListener {
                override fun onProgress(done: ULong, total: ULong, currentPath: String) {
                    _state.update { it.copy(scanProgress = "Scanned $done files") }
                }
            }

            runCatching { repository.analyze(rootPath, 50u, progress, token) }
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
                            scanProgress = "",
                        )
                    }
                }
                .onFailure {
                    // A cancelled scan lands here too, which is the expected
                    // path when the user leaves the screen.
                    _state.update { s -> s.copy(isLoading = false, scanProgress = "") }
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

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
import java.io.File
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
    /**
     * The copies ticked for the trash in each duplicate set, by its hash. The
     * oldest of each starts unticked - the one kept when nothing is changed,
     * as the single button used to keep it - and the rest ticked.
     */
    val duplicateMarks: Map<String, Set<String>> = emptyMap(),
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

    /** Where the duplicates were looked for, which their paths are shown from. */
    val root: String get() = rootPath

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
                            // Not "freed": the trash is on the same storage,
                            // so nothing is released until it is emptied.
                            // Claiming otherwise sent people looking for
                            // space that had not appeared.
                            message = "${paths.size} moved to trash. " +
                                "Empty the trash to free ${formatSize(freed)}.",
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

    /**
     * One token per job, not one shared.
     *
     * They were shared, so tapping "Find duplicates" while the analysis was
     * still running cancelled the analysis - and the per-category breakdown,
     * which only that walk fills in, stayed empty for as long as the screen
     * was open.
     */
    private var analysis: CancelToken? = null
    private var duplicateScan: CancelToken? = null

    init {
        load()
    }

    fun load() {
        analysis?.cancel()
        val token = CancelToken()
        analysis = token

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
                .onSuccess { result ->
                    // A scan that has since been replaced says nothing about
                    // whether the current one is still loading.
                    if (analysis !== token) return@onSuccess
                    _state.update {
                        it.copy(
                            summary = StorageSummary(
                                totalBytes = result.totalBytes,
                                freeBytes = result.freeBytes,
                                scannedBytes = result.scannedBytes,
                                byCategory = result.byCategory,
                            ),
                            largest = result.largest,
                            isLoading = false,
                        )
                    }
                }
                .onFailure {
                    // A cancelled scan lands here too, which is the expected
                    // path when the user leaves the screen.
                    if (analysis !== token) return@onFailure
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
        duplicateScan?.cancel()
        val token = CancelToken()
        duplicateScan = token

        _state.update { it.copy(isScanningDuplicates = true, duplicates = emptyList()) }
        viewModelScope.launch {
            val progress = object : ProgressListener {
                override fun onProgress(done: ULong, total: ULong, currentPath: String) {
                    if (duplicateScan !== token) return
                    _state.update { it.copy(duplicateProgress = "Checking $done of $total groups") }
                }
            }

            runCatching { repository.duplicates(rootPath, MIN_DUPLICATE_SIZE, progress, token) }
                .onSuccess { groups ->
                    if (duplicateScan !== token) return@onSuccess
                    _state.update {
                        it.copy(
                            duplicates = groups,
                            duplicateMarks = groups.associate { group ->
                                group.hash to group.files.drop(1).mapTo(HashSet()) { file -> file.path }
                            },
                            isScanningDuplicates = false,
                            duplicateProgress = "",
                        )
                    }
                }
                .onFailure {
                    if (duplicateScan !== token) return@onFailure
                    _state.update { s -> s.copy(isScanningDuplicates = false, duplicateProgress = "") }
                }
        }
    }

    /** Tick or untick one copy in a duplicate set for the trash. */
    fun toggleDuplicate(hash: String, path: String) = _state.update { current ->
        val marks = current.duplicateMarks[hash].orEmpty()
        val next = if (path in marks) marks - path else marks + path
        current.copy(duplicateMarks = current.duplicateMarks + (hash to next))
    }

    /**
     * Trash the copies ticked in [group].
     *
     * Which ones used to be decided for the user - every copy but the oldest,
     * with no way to see where any of them was. Each is listed with its folder
     * now, and the user ticks the ones to go: any of them, or every one.
     */
    fun trashDuplicates(group: DuplicateGroup) {
        val marked = _state.value.duplicateMarks[group.hash].orEmpty()
        if (marked.isEmpty()) return

        viewModelScope.launch {
            val result = runCatching { repository.moveToTrash(marked.toList()) }
            // From the disk rather than the result: a failure partway through
            // leaves some trashed and some not.
            val left = group.files.filter { File(it.path).exists() }
            val trashed = group.files.size - left.size
            _state.update { current ->
                current.copy(
                    duplicates = current.duplicates.mapNotNull { set ->
                        when {
                            set.hash != group.hash -> set
                            // A single copy is not a duplicate any more.
                            left.size < 2 -> null
                            else -> set.copy(files = left, wastedBytes = set.size * (left.size - 1).toULong())
                        }
                    },
                    duplicateMarks = if (left.size < 2) {
                        current.duplicateMarks - group.hash
                    } else {
                        current.duplicateMarks + (group.hash to emptySet())
                    },
                    message = result.fold(
                        onSuccess = {
                            when (left.size) {
                                0 -> "Moved all $trashed copies to trash"
                                1 -> "Moved $trashed to trash, kept ${left.single().name}"
                                else -> "Moved $trashed to trash"
                            }
                        },
                        // Said. A failure here used to leave the set on screen
                        // with nothing to indicate the button had done anything.
                        onFailure = { it.userMessage("Could not move them to the trash") },
                    ),
                )
            }
        }
    }

    fun cancelScan() {
        duplicateScan?.cancel()
        _state.update { it.copy(isScanningDuplicates = false, duplicateProgress = "") }
    }

    override fun onCleared() {
        analysis?.cancel()
        duplicateScan?.cancel()
        super.onCleared()
    }

    private companion object {
        /** Ignore anything under 100 KB -- small duplicates are mostly caches. */
        val MIN_DUPLICATE_SIZE: ULong = 100uL * 1024uL
    }
}

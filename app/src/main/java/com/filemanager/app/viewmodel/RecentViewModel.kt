package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.CancelToken
import uniffi.filemanager_core.FileEntry

data class RecentState(
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = true,
    val selected: Set<String> = emptySet(),
    val selectionActive: Boolean = false,
    val message: String? = null,
) {
    val inSelectionMode: Boolean get() = selectionActive || selected.isNotEmpty()
    val allSelected: Boolean
        get() = entries.isNotEmpty() && selected.size == entries.size
}

/**
 * Recent files, with the same selection and actions as everywhere else.
 *
 * It has its own view model rather than borrowing the home screen's, which
 * held the list read-only - so the rows could be looked at and opened but not
 * acted on, unlike every other list in the app.
 */
class RecentViewModel(
    private val repository: FileRepository,
    private val clipboard: FileClipboard,
    private val rootPath: String,
) : ViewModel() {

    private val _state = MutableStateFlow(RecentState())
    val state: StateFlow<RecentState> = _state.asStateFlow()

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
            runCatching { repository.recent(rootPath, days = 7u, limit = 200u, cancel = token) }
                .onSuccess { entries ->
                    _state.update { it.copy(entries = entries, isLoading = false) }
                }
                .onFailure {
                    // Cancellation lands here too, which is the expected path
                    // when the user leaves the screen.
                    _state.update { it.copy(isLoading = false) }
                }
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

    fun copySelection() {
        clipboard.copy(_state.value.selected.toList())
        // Out of selection mode as well, or the screen stayed in it with
        // nothing ticked when selection had been entered from the menu.
        _state.update {
            it.copy(
                selected = emptySet(),
                selectionActive = false,
                message = "Copied. Paste in any folder.",
            )
        }
    }

    fun cutSelection() {
        clipboard.cut(_state.value.selected.toList())
        _state.update {
            it.copy(
                selected = emptySet(),
                selectionActive = false,
                message = "Cut. Paste in any folder.",
            )
        }
    }

    fun deleteSelection() {
        val paths = _state.value.selected.toList()
        if (paths.isEmpty()) return

        viewModelScope.launch {
            runCatching { repository.moveToTrash(paths) }
                .onSuccess {
                    val gone = paths.toSet()
                    _state.update { current ->
                        current.copy(
                            entries = current.entries.filterNot { it.path in gone },
                            selected = emptySet(),
                            selectionActive = false,
                            message = "${paths.size} moved to trash",
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(message = e.userMessage("Could not delete")) }
                }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        scan?.cancel()
        super.onCleared()
    }
}

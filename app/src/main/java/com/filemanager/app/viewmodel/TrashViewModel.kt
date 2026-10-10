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
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.TrashItem

/** One item in the trash, and the entry its row draws: see FileRepository.trashRows. */
data class TrashRow(val item: TrashItem, val entry: FileEntry)

/**
 * The trash's details sheet, for one item or for several together. What is
 * read from disk - when each was last changed, what a folder holds - is null
 * until it comes back.
 */
data class TrashDetails(
    val items: List<TrashItem>,
    /** When each of [items] was last changed before it was deleted, in their order. */
    val modifiedMs: List<Long>? = null,
    /** For one folder: the files and folders inside it. */
    val contents: Pair<ULong, ULong>? = null,
)

data class TrashState(
    val rows: List<TrashRow> = emptyList(),
    val totalBytes: ULong = 0uL,
    val isLoading: Boolean = true,
    val message: String? = null,
    /** Ids of the items ticked. */
    val selected: Set<String> = emptySet(),
    val selectionActive: Boolean = false,
    val details: TrashDetails? = null,
) {
    val inSelectionMode: Boolean get() = selectionActive || selected.isNotEmpty()
    val allSelected: Boolean get() = rows.isNotEmpty() && selected.size == rows.size
    val selectedItems: List<TrashItem> get() = rows.map { it.item }.filter { it.id in selected }
}

/**
 * The trash, laid out and acted on as One UI's is: grouped by how long each
 * item has left, and chosen like files anywhere else - then restored, looked
 * at or deleted for good, a few at a time or all at once.
 */
class TrashViewModel(private val repository: FileRepository) : ViewModel() {

    private val _state = MutableStateFlow(TrashState())
    val state: StateFlow<TrashState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            runCatching { repository.trashRows() to repository.trashBytes() }
                .onSuccess { (rows, bytes) ->
                    _state.update { current ->
                        val ids = rows.mapTo(HashSet()) { it.first.id }
                        current.copy(
                            rows = rows.map { (item, entry) -> TrashRow(item, entry) },
                            totalBytes = bytes,
                            isLoading = false,
                            // Ticks for items that have since gone are dropped.
                            selected = current.selected.filterTo(HashSet()) { it in ids },
                        )
                    }
                }
                .onFailure {
                    // userMessage, not message: the core's errors carry an
                    // empty message for most variants, which showed a blank
                    // snackbar that looked like nothing had happened.
                    _state.update { s ->
                        s.copy(isLoading = false, message = it.userMessage("Could not open the trash"))
                    }
                }
        }
    }

    // --- Selection ------------------------------------------------------------

    // Choosing one starts selecting, and selecting goes on with nothing
    // ticked - "Select items" - until Cancel or Back, as One UI has it.
    // Unticking the last one used to end it.
    fun toggleSelection(id: String) = _state.update { current ->
        current.copy(
            selected = if (id in current.selected) current.selected - id else current.selected + id,
            selectionActive = true,
        )
    }

    fun toggleSelectAll() = _state.update { current ->
        current.copy(
            selected = if (current.allSelected) emptySet() else current.rows.mapTo(HashSet()) { it.item.id },
            selectionActive = true,
        )
    }

    fun enterSelectionMode() = _state.update { it.copy(selectionActive = true) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet(), selectionActive = false) }

    // --- Actions --------------------------------------------------------------

    /**
     * Put the selection back where each item came from. Each goes back on its
     * own: one that cannot - something new is at its old place - is said, and
     * does not hold the others in the trash.
     */
    fun restoreSelected() {
        val items = _state.value.selectedItems
        if (items.isEmpty()) return
        viewModelScope.launch {
            val failed = items.mapNotNull { item ->
                runCatching { repository.restoreFromTrash(item.id) }.exceptionOrNull()?.let { item to it }
            }
            val restored = items.size - failed.size
            val message = when {
                failed.isEmpty() -> if (restored == 1) "Restored ${items.single().name}" else "Restored $restored items"
                restored == 0 -> failed.first().let { (item, error) -> couldNot("restore", item, error) }
                else -> "Restored $restored, but not ${failed.first().first.name}" +
                    if (failed.size > 1) " and ${failed.size - 1} more" else ""
            }
            _state.update { it.copy(selected = emptySet(), selectionActive = false, message = message) }
            refresh()
        }
    }

    /** Delete the selection for good. Asked first, by the screen. */
    fun deleteSelected() {
        val items = _state.value.selectedItems
        if (items.isEmpty()) return
        viewModelScope.launch {
            val failed = items.mapNotNull { item ->
                runCatching { repository.deleteFromTrash(item.id) }.exceptionOrNull()?.let { item to it }
            }
            val deleted = items.size - failed.size
            val message = when {
                failed.isEmpty() -> if (deleted == 1) "Deleted ${items.single().name}" else "Deleted $deleted items"
                deleted == 0 -> failed.first().let { (item, error) -> couldNot("delete", item, error) }
                else -> "Deleted $deleted, but not ${failed.first().first.name}" +
                    if (failed.size > 1) " and ${failed.size - 1} more" else ""
            }
            _state.update { it.copy(selected = emptySet(), selectionActive = false, message = message) }
            refresh()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            runCatching { repository.emptyTrash() }
                .onSuccess { count ->
                    _state.update { it.copy(message = "Deleted $count items permanently") }
                    refresh()
                }
                .onFailure {
                    // Said, and the list reloaded, where before a failure did
                    // neither - the button was pressed, nothing changed on
                    // screen, and whatever had in fact gone was still listed.
                    _state.update { s -> s.copy(message = it.userMessage("Could not empty the trash")) }
                    refresh()
                }
        }
    }

    // --- Details --------------------------------------------------------------

    /** Open the details of what is selected: one item, or the lot together. */
    fun showDetails() {
        val items = _state.value.selectedItems
        if (items.isEmpty()) return
        _state.update { it.copy(details = TrashDetails(items)) }
        viewModelScope.launch {
            val modified = runCatching { repository.trashedModifiedMs(items.map { it.id }) }.getOrNull()
            // A folder's contents need a walk of everything inside it.
            val contents = items.singleOrNull()?.takeIf { it.isDir }?.let { folder ->
                runCatching { repository.stats(listOf(repository.trashedPath(folder.id))) }.getOrNull()
                    // The walk counts the folder it starts from.
                    ?.let { it.fileCount to (if (it.dirCount > 0uL) it.dirCount - 1uL else 0uL) }
            }
            _state.update { current ->
                // Dropped if the sheet was closed, or opened on something else.
                if (current.details?.items != items) return@update current
                current.copy(details = current.details.copy(modifiedMs = modified, contents = contents))
            }
        }
    }

    fun dismissDetails() = _state.update { it.copy(details = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * "Could not restore notes.txt: something with that name is already
     * there" - the item named, as several may have been chosen, and why.
     */
    private fun couldNot(verb: String, item: TrashItem, error: Throwable): String {
        val why = error.userMessage("").replaceFirstChar { it.lowercase() }
        return if (why.isBlank()) "Could not $verb ${item.name}" else "Could not $verb ${item.name}: $why"
    }
}

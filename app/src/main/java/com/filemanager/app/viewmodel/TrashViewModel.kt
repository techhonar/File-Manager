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
import uniffi.filemanager_core.TrashItem

data class TrashState(
    val items: List<TrashItem> = emptyList(),
    val totalBytes: ULong = 0uL,
    val isLoading: Boolean = true,
    val message: String? = null,
)

class TrashViewModel(private val repository: FileRepository) : ViewModel() {

    private val _state = MutableStateFlow(TrashState())
    val state: StateFlow<TrashState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            runCatching { repository.trashContents() to repository.trashBytes() }
                .onSuccess { (items, bytes) ->
                    _state.update { it.copy(items = items, totalBytes = bytes, isLoading = false) }
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

    fun restore(item: TrashItem) {
        viewModelScope.launch {
            runCatching { repository.restoreFromTrash(item.id) }
                .onSuccess { path ->
                    _state.update { it.copy(message = "Restored to $path") }
                    refresh()
                }
                .onFailure {
                    // The usual cause is something new sitting at the original
                    // path, which is worth saying plainly.
                    _state.update { s ->
                        s.copy(message = it.userMessage("Could not restore ${item.name}"))
                    }
                }
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

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

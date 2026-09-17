package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.remote.RemoteRepository
import com.filemanager.app.data.remote.RemoteServer
import com.filemanager.app.data.remote.RemoteServers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How a connection test is going, so the form can say rather than guess. */
enum class TestState { IDLE, RUNNING, PASSED, FAILED }

data class RemoteServersState(
    val servers: List<RemoteServer> = emptyList(),
    /** Set while the add/edit sheet is open. A new server is one with a blank
     *  host, which is what [RemoteServer]'s defaults give. */
    val editing: RemoteServer? = null,
    val testState: TestState = TestState.IDLE,
    val testMessage: String? = null,
    val message: String? = null,
)

class RemoteServersViewModel(
    private val servers: RemoteServers,
    private val repository: RemoteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RemoteServersState())
    val state: StateFlow<RemoteServersState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            servers.servers.collect { list -> _state.update { it.copy(servers = list) } }
        }
    }

    fun addNew() = _state.update {
        it.copy(editing = RemoteServer(), testState = TestState.IDLE, testMessage = null)
    }

    fun edit(server: RemoteServer) = _state.update {
        it.copy(editing = server, testState = TestState.IDLE, testMessage = null)
    }

    fun cancelEdit() = _state.update {
        it.copy(editing = null, testState = TestState.IDLE, testMessage = null)
    }

    /**
     * Update the server being edited.
     *
     * Any edit clears a previous test result: a green tick left over from
     * before the host was changed would be saying something untrue.
     */
    fun updateDraft(server: RemoteServer) = _state.update {
        it.copy(editing = server, testState = TestState.IDLE, testMessage = null)
    }

    fun test() {
        val draft = _state.value.editing ?: return
        draft.validate()?.let { problem ->
            _state.update { it.copy(testState = TestState.FAILED, testMessage = problem) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(testState = TestState.RUNNING, testMessage = null) }
            runCatching { repository.test(draft) }
                .onSuccess {
                    _state.update {
                        it.copy(testState = TestState.PASSED, testMessage = "Connected")
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            testState = TestState.FAILED,
                            testMessage = failure.message ?: "Could not connect",
                        )
                    }
                }
        }
    }

    /** Saves the draft, or reports what is missing. Returns nothing: the sheet
     *  closes only when [RemoteServersState.editing] goes null. */
    fun save() {
        val draft = _state.value.editing ?: return
        draft.validate()?.let { problem ->
            _state.update { it.copy(testState = TestState.FAILED, testMessage = problem) }
            return
        }
        // Drop any held connection: it was opened with the old settings, and
        // handing it back after an edit would ignore everything just changed.
        repository.forget(draft.id)
        servers.save(draft)
        _state.update {
            it.copy(editing = null, testState = TestState.IDLE, testMessage = null)
        }
    }

    fun delete(server: RemoteServer) {
        repository.forget(server.id)
        servers.remove(server.id)
        _state.update { it.copy(message = "Removed ${server.label}", editing = null) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

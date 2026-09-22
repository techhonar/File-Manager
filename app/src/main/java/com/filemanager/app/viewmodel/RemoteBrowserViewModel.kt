package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.freeName
import com.filemanager.app.data.freeRemoteName
import com.filemanager.app.data.remote.RemoteEntry
import com.filemanager.app.data.remote.RemotePaths
import com.filemanager.app.data.remote.RemoteRepository
import com.filemanager.app.data.remote.RemoteServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class RemoteBrowserState(
    val server: RemoteServer,
    val path: String = "/",
    val entries: List<RemoteEntry> = emptyList(),
    val isLoading: Boolean = true,
    /** Set when the listing itself failed, so the screen can offer Retry
     *  rather than showing an empty folder that is not empty. */
    val error: String? = null,
    val selected: Set<String> = emptySet(),
    val selectionActive: Boolean = false,
    /** What long-running job is in flight, for the progress line. */
    val busy: String? = null,
    val message: String? = null,
    /** Set by the view model, which normalises what the form stored. */
    val basePath: String = "/",
) {
    val inSelectionMode: Boolean get() = selectionActive || selected.isNotEmpty()

    val allSelected: Boolean
        get() = entries.isNotEmpty() && selected.size == entries.size

    val atRoot: Boolean get() = path == "/" || path == basePath

    val selectedEntries: List<RemoteEntry>
        get() = entries.filter { it.path in selected }
}

/**
 * Browsing one network server.
 *
 * Deliberately separate from BrowserViewModel rather than an extra mode on it.
 * That one is built on paths the Rust core can walk: it watches the folder for
 * changes, pins and favourites by path, computes folder sizes, and sorts using
 * the shared settings. None of that has a meaning over a socket, and threading
 * a second kind of path through all of it would put a branch in every one of
 * those features to serve a screen that needs none of them.
 */
class RemoteBrowserViewModel(
    server: RemoteServer,
    private val repository: RemoteRepository,
    private val clipboard: FileClipboard,
    /** Only to finish a move: the originals are local files. */
    private val localFiles: FileRepository,
) : ViewModel() {

    // Normalised once. The folder is typed into a form, so it arrives with a
    // trailing slash or a doubled one as often as not - and every path the
    // clients return is normalised, so an un-normalised start never compares
    // equal to anything and the screen thinks it is never at the top.
    private val basePath = RemotePaths.normalise(server.basePath)

    private val _state = MutableStateFlow(
        RemoteBrowserState(server = server, path = basePath, basePath = basePath),
    )
    val state: StateFlow<RemoteBrowserState> = _state.asStateFlow()

    /**
     * A file that has been fetched and is waiting to be handed to another app.
     *
     * The view model cannot start an intent - it has no Context, and holding
     * one is how activities leak - so it puts the file here and the screen
     * picks it up.
     */
    private val _openRequest = MutableStateFlow<File?>(null)
    val openRequest: StateFlow<File?> = _openRequest.asStateFlow()

    /** What the local browser has on its clipboard, so Paste can offer it. */
    val clipboardContents = clipboard.contents

    init {
        load(basePath)
    }

    fun load(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, selected = emptySet()) }
            runCatching { repository.list(_state.value.server, path) }
                .onSuccess { entries ->
                    _state.update {
                        it.copy(path = path, entries = entries, isLoading = false, error = null)
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            // The path still moves, so Back works and the
                            // breadcrumb matches what was attempted.
                            path = path,
                            entries = emptyList(),
                            error = failure.message ?: "Could not open that folder",
                        )
                    }
                }
        }
    }

    fun refresh() = load(_state.value.path)

    /** True when it handled the press, so the screen knows not to navigate. */
    fun navigateUp(): Boolean {
        val current = _state.value
        if (current.inSelectionMode) {
            clearSelection()
            return true
        }
        if (current.atRoot) return false
        load(RemotePaths.parent(current.path))
        return true
    }

    fun open(entry: RemoteEntry) {
        if (entry.isDir) {
            load(entry.path)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = "Opening ${entry.name}…") }
            runCatching { repository.cacheForOpening(_state.value.server, entry) }
                .onSuccess { file ->
                    _state.update { it.copy(busy = null) }
                    _openRequest.value = file
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(busy = null, message = failure.message ?: "Could not open it")
                    }
                }
        }
    }

    fun consumeOpenRequest() {
        _openRequest.value = null
    }

    // --- Selection ----------------------------------------------------------

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

    // --- File operations ----------------------------------------------------

    /** Copy the selection into a local folder. */
    fun downloadSelection(into: File) {
        val entries = _state.value.selectedEntries.filterNot { it.isDir }
        if (entries.isEmpty()) {
            _state.update { it.copy(message = "Select files to download") }
            return
        }

        viewModelScope.launch {
            var done = 0
            var failed = 0
            for (entry in entries) {
                _state.update {
                    it.copy(busy = "Downloading ${done + 1} of ${entries.size}…")
                }
                // Never the name of a file already there. Downloading straight
                // to Downloads/<name> replaced any file of the user's with that
                // name - and on failure the cleanup below then deleted it, so a
                // server that was merely unreachable destroyed a local file the
                // download had never touched.
                val target = freeName(
                    into,
                    File(entry.name).nameWithoutExtension,
                    File(entry.name).extension,
                )
                runCatching { repository.download(_state.value.server, entry, target) }
                    .onSuccess { done++ }
                    .onFailure {
                        failed++
                        // A half-written file is worse than none: it looks like
                        // a download that worked. Safe to remove now that the
                        // name was free before this began - it is ours.
                        runCatching { target.delete() }
                    }
            }
            _state.update {
                it.copy(
                    busy = null,
                    selected = emptySet(),
                    selectionActive = false,
                    message = if (failed == 0) {
                        "Downloaded $done to ${into.name}"
                    } else {
                        "Downloaded $done, $failed failed"
                    },
                )
            }
        }
    }

    /**
     * Upload whatever the local browser copied, or cut.
     *
     * A cut that only uploaded was the surprise here: the files appeared on the
     * server and stayed on the phone, and nothing said so. The originals now go
     * once their upload has been confirmed - and only those, so a transfer that
     * failed half way leaves the rest where they are.
     *
     * Folders are skipped either way. There is no recursive upload here, and
     * quietly flattening one would be worse than saying it was left.
     */
    fun pasteFromClipboard() {
        val pending = clipboard.contents.value
        val paths = pending?.paths.orEmpty()
        if (paths.isEmpty()) {
            _state.update { it.copy(message = "Nothing copied") }
            return
        }

        viewModelScope.launch {
            val files = paths.map(::File).filter { it.isFile }
            val skipped = paths.size - files.size
            val uploaded = mutableListOf<String>()
            var failed = 0

            // Once for the whole paste, and grown as each name is used, so two
            // files with the same name - from different folders - do not both
            // take it and land on top of each other.
            val taken = _state.value.entries.mapTo(HashSet()) { it.name }
            for (file in files) {
                _state.update {
                    it.copy(busy = "Uploading ${uploaded.size + failed + 1} of ${files.size}…")
                }
                // Not over a file already on the server. Uploads replaced
                // anything with the same name without asking; the folder's
                // listing is already in hand, so a clash costs nothing to see.
                val name = freeRemoteName(taken, file.nameWithoutExtension, file.extension)
                taken += name
                val target = RemotePaths.join(_state.value.path, name)
                runCatching { repository.upload(_state.value.server, file, target) }
                    .onSuccess { uploaded += file.absolutePath }
                    .onFailure { failed++ }
            }

            var moved = 0
            if (pending?.isMove == true && uploaded.isNotEmpty()) {
                _state.update { it.copy(busy = "Removing the originals…") }
                // To the trash rather than deleted outright, which is what a
                // move does everywhere else in the app and leaves a way back.
                moved = runCatching { localFiles.moveToTrash(uploaded).size }.getOrDefault(0)
            }

            // Only once it has all been dealt with: leaving it would invite a
            // second paste that moves nothing, having already moved it.
            if (failed == 0) clipboard.clear()

            _state.update {
                it.copy(
                    busy = null,
                    message = buildString {
                        append(if (pending?.isMove == true) "Moved " else "Uploaded ")
                        append(uploaded.size)
                        if (pending?.isMove == true && moved < uploaded.size) {
                            append(" (originals kept)")
                        }
                        if (failed > 0) append(", $failed failed")
                        if (skipped > 0) append(", $skipped folders skipped")
                    },
                )
            }
            refresh()
        }
    }

    fun deleteSelection() {
        val entries = _state.value.selectedEntries
        if (entries.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(busy = "Deleting…") }
            var failed = 0
            for (entry in entries) {
                runCatching { repository.delete(_state.value.server, entry) }
                    .onFailure { failed++ }
            }
            _state.update {
                it.copy(
                    busy = null,
                    selected = emptySet(),
                    selectionActive = false,
                    // There is no trash on a remote server, so this is final.
                    message = if (failed == 0) {
                        "Deleted ${entries.size}"
                    } else {
                        "Deleted ${entries.size - failed}, $failed failed"
                    },
                )
            }
            refresh()
        }
    }

    fun rename(entry: RemoteEntry, newName: String) {
        if (newName.isBlank() || newName == entry.name) return
        viewModelScope.launch {
            runCatching { repository.rename(_state.value.server, entry, newName) }
                .onFailure { failure ->
                    _state.update { it.copy(message = failure.message ?: "Could not rename it") }
                }
            clearSelection()
            refresh()
        }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching {
                repository.makeDirectory(_state.value.server, _state.value.path, name)
            }.onFailure { failure ->
                _state.update {
                    it.copy(message = failure.message ?: "Could not create the folder")
                }
            }
            refresh()
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

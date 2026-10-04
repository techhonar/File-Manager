package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.remote.RemoteEntry
import com.filemanager.app.data.remote.RemotePaths
import com.filemanager.app.data.remote.RemoteRepository
import com.filemanager.app.data.remote.RemoteServer
import com.filemanager.app.data.transfer.Direction
import com.filemanager.app.data.transfer.Pacer
import com.filemanager.app.data.transfer.Transfer
import com.filemanager.app.data.transfer.TransferCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.formatSize
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
    /** How far that job has got, when that is known; the line runs without
     *  an end otherwise. */
    val progress: Float? = null,
    /** The transfer the line is about, which can be stopped from it. Null for
     *  anything else. */
    val transferId: Int? = null,
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
    /** Where downloads and uploads run: they outlive this screen. */
    private val transfers: TransferCenter,
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
        // A transfer started here carries on when the screen is left; coming
        // back to the server, it is shown again.
        transfers.active.value
            .filter { it.serverId == server.id }
            .forEach { running -> transfers.follow(running.id)?.let(::follow) }
    }

    /**
     * The listing in flight. Each new one replaces it: a folder opened and
     * backed out of before its listing arrived used to land after the parent's,
     * leaving the user in the folder they had just left.
     */
    private var loadJob: Job? = null

    fun load(path: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, selected = emptySet()) }
            runCatching { repository.list(_state.value.server, path) }
                // A superseded listing is not a failure to report.
                .onFailure { if (it is CancellationException) throw it }
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
            _state.update { it.copy(busy = "Opening ${entry.name}…", progress = null) }
            // Opening fetches the whole file first, which for a video is a
            // download in all but name; the line says how far it has got.
            val pacer = Pacer(PROGRESS_INTERVAL_MS)
            runCatching {
                repository.cacheForOpening(_state.value.server, entry) { bytes ->
                    if (entry.size > 0 && pacer.due()) {
                        _state.update { it.copy(progress = (bytes.toFloat() / entry.size).coerceIn(0f, 1f)) }
                    }
                }
            }
                .onSuccess { file ->
                    _state.update { it.copy(busy = null, progress = null) }
                    _openRequest.value = file
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(busy = null, progress = null, message = failure.message ?: "Could not open it")
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

    /**
     * Copy the selection into a local folder.
     *
     * Through the app's transfers rather than here, so it goes on - with its
     * progress in a notification - when the user leaves the screen. See
     * TransferCenter for what it does with names already taken.
     */
    fun downloadSelection(into: File) {
        val entries = _state.value.selectedEntries.filterNot { it.isDir }
        if (entries.isEmpty()) {
            _state.update { it.copy(message = "Select files to download") }
            return
        }
        _state.update { it.copy(selected = emptySet(), selectionActive = false) }
        follow(transfers.download(_state.value.server, entries, into))
    }

    /**
     * Upload whatever the local browser copied, or cut.
     *
     * A cut that only uploaded was the surprise here: the files appeared on the
     * server and stayed on the phone, and nothing said so. The originals now go
     * once their upload has been confirmed - see TransferCenter.upload.
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
        val files = paths.map(::File).filter { it.isFile }
        if (files.isEmpty()) {
            _state.update { it.copy(message = "Folders cannot be uploaded, only files") }
            return
        }
        val current = _state.value
        follow(
            transfers.upload(
                server = current.server,
                files = files,
                folder = current.path,
                taken = current.entries.mapTo(HashSet()) { it.name },
                move = pending?.isMove == true,
                skipped = paths.size - files.size,
            ),
        )
    }

    /** Stop the transfer on the progress line. */
    fun cancelTransfer() {
        _state.value.transferId?.let(transfers::cancel)
    }

    /**
     * Show [transfer] on the progress line until it is over, then say how it
     * went - and, after an upload, list the folder again to show the files.
     */
    private fun follow(transfer: StateFlow<Transfer>) {
        viewModelScope.launch {
            val last = transfer
                .onEach { t ->
                    _state.update {
                        it.copy(busy = progressLine(t), progress = t.fraction, transferId = t.id)
                    }
                }
                .first { it.outcome != null }
            _state.update {
                it.copy(busy = null, progress = null, transferId = null, message = last.outcome?.summary)
            }
            if (last.direction == Direction.UPLOAD) refresh()
        }
    }

    /** "Downloading 2 of 5 · 12 MB of 450 MB", or the file's name for one. */
    private fun progressLine(transfer: Transfer): String {
        val verb = if (transfer.direction == Direction.DOWNLOAD) "Downloading" else "Uploading"
        val which = if (transfer.fileCount == 1) {
            transfer.current ?: "1 file"
        } else {
            "${(transfer.filesDone + 1).coerceAtMost(transfer.fileCount)} of ${transfer.fileCount}"
        }
        val amount = if (transfer.bytesTotal > 0) {
            "${size(transfer.bytesDone)} of ${size(transfer.bytesTotal)}"
        } else {
            size(transfer.bytesDone)
        }
        return "$verb $which · $amount"
    }

    private fun size(bytes: Long): String = formatSize(bytes.coerceAtLeast(0).toULong())

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

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
